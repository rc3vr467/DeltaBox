/* SPDX-License-Identifier: MIT
 *
 * Copyright (C) 2017-2025 WireGuard LLC. All Rights Reserved.
 */

package device

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/wireguard-go/ipc"
)

type IPCError struct {
	code int64 // error code
	err  error // underlying/wrapped error
}

func (s IPCError) Error() string {
	return fmt.Sprintf("IPC error %d: %v", s.code, s.err)
}

func (s IPCError) Unwrap() error {
	return s.err
}

func (s IPCError) ErrorCode() int64 {
	return s.code
}

func ipcErrorf(code int64, msg string, args ...any) *IPCError {
	return &IPCError{code: code, err: fmt.Errorf(msg, args...)}
}

var byteBufferPool = &sync.Pool{
	New: func() any { return new(bytes.Buffer) },
}

// IpcGetOperation implements the WireGuard configuration protocol "get" operation.
// See https://www.wireguard.com/xplatform/#configuration-protocol for details.
func (device *Device) IpcGetOperation(w io.Writer) error {
	device.ipcMutex.RLock()
	defer device.ipcMutex.RUnlock()

	buf := byteBufferPool.Get().(*bytes.Buffer)
	buf.Reset()
	defer byteBufferPool.Put(buf)
	sendf := func(format string, args ...any) {
		fmt.Fprintf(buf, format, args...)
		buf.WriteByte('\n')
	}
	keyf := func(prefix string, key *[32]byte) {
		buf.Grow(len(key)*2 + 2 + len(prefix))
		buf.WriteString(prefix)
		buf.WriteByte('=')
		const hex = "0123456789abcdef"
		for i := 0; i < len(key); i++ {
			buf.WriteByte(hex[key[i]>>4])
			buf.WriteByte(hex[key[i]&0xf])
		}
		buf.WriteByte('\n')
	}

	func() {
		// lock required resources

		device.net.RLock()
		defer device.net.RUnlock()

		device.staticIdentity.RLock()
		defer device.staticIdentity.RUnlock()

		device.peers.RLock()
		defer device.peers.RUnlock()

		// serialize device related values

		if !device.staticIdentity.privateKey.IsZero() {
			keyf("private_key", (*[32]byte)(&device.staticIdentity.privateKey))
		}

		if device.net.port != 0 {
			sendf("listen_port=%d", device.net.port)
		}

		if device.net.fwmark != 0 {
			sendf("fwmark=%d", device.net.fwmark)
		}

		// --- AmneziaWG: report junk-packet / magic-header obfuscation state ---
		if count := device.junk.count.Load(); count != 0 {
			sendf("jc=%d", count)
		}
		if min := device.junk.min.Load(); min != 0 {
			sendf("jmin=%d", min)
		}
		if max := device.junk.max.Load(); max != 0 {
			sendf("jmax=%d", max)
		}
		if padding := device.paddings.init.Load(); padding != 0 {
			sendf("s1=%d", padding)
		}
		if padding := device.paddings.response.Load(); padding != 0 {
			sendf("s2=%d", padding)
		}
		if padding := device.paddings.cookie.Load(); padding != 0 {
			sendf("s3=%d", padding)
		}
		if padding := device.paddings.transport.Load(); padding != 0 {
			sendf("s4=%d", padding)
		}
		if header := device.headers.init.Load(); !header.IsZero() {
			sendf("h1=%s", header.ToString())
		}
		if header := device.headers.response.Load(); !header.IsZero() {
			sendf("h2=%s", header.ToString())
		}
		if header := device.headers.cookie.Load(); !header.IsZero() {
			sendf("h3=%s", header.ToString())
		}
		if header := device.headers.transport.Load(); !header.IsZero() {
			sendf("h4=%s", header.ToString())
		}
		device.headerProtection.RLock()
		if !device.headerProtection.key.IsZero() {
			keyf("header_protection_key", (*[32]byte)(&device.headerProtection.key))
		}
		device.headerProtection.RUnlock()

		for _, peer := range device.peers.keyMap {
			// Serialize peer state.
			peer.handshake.mutex.RLock()
			keyf("public_key", (*[32]byte)(&peer.handshake.remoteStatic))
			keyf("preshared_key", (*[32]byte)(&peer.handshake.presharedKey))
			peer.handshake.mutex.RUnlock()
			sendf("protocol_version=1")
			peer.endpoint.Lock()
			if peer.endpoint.val != nil {
				sendf("endpoint=%s", peer.endpoint.val.DstToString())
			}
			peer.endpoint.Unlock()

			nano := peer.lastHandshakeNano.Load()
			secs := nano / time.Second.Nanoseconds()
			nano %= time.Second.Nanoseconds()

			sendf("last_handshake_time_sec=%d", secs)
			sendf("last_handshake_time_nsec=%d", nano)
			sendf("tx_bytes=%d", peer.txBytes.Load())
			sendf("rx_bytes=%d", peer.rxBytes.Load())
			sendf("persistent_keepalive_interval=%d", peer.persistentKeepaliveInterval.Load())

			device.allowedips.EntriesForPeer(peer, func(prefix netip.Prefix) bool {
				sendf("allowed_ip=%s", prefix.String())
				return true
			})
		}
	}()

	// send lines (does not require resource locks)
	if _, err := w.Write(buf.Bytes()); err != nil {
		return ipcErrorf(ipc.IpcErrorIO, "failed to write output: %w", err)
	}

	return nil
}

// IpcSetOperation implements the WireGuard configuration protocol "set" operation.
// See https://www.wireguard.com/xplatform/#configuration-protocol for details.
func (device *Device) IpcSetOperation(r io.Reader) (err error) {
	device.ipcMutex.Lock()
	defer device.ipcMutex.Unlock()

	defer func() {
		if err != nil {
			device.log.Errorf("%v", err)
		}
	}()

	peer := new(ipcSetPeer)
	deviceConfig := true

	scanner := bufio.NewScanner(r)
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			// Blank line means terminate operation.
			peer.handlePostConfig()
			return nil
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			return ipcErrorf(ipc.IpcErrorProtocol, "failed to parse line %q", line)
		}

		if key == "public_key" {
			if deviceConfig {
				deviceConfig = false
			}
			peer.handlePostConfig()
			// Load/create the peer we are now configuring.
			err := device.handlePublicKeyLine(peer, value)
			if err != nil {
				return err
			}
			continue
		}

		var err error
		if deviceConfig {
			err = device.handleDeviceLine(key, value)
		} else {
			err = device.handlePeerLine(peer, key, value)
		}
		if err != nil {
			return err
		}
	}
	peer.handlePostConfig()

	if err := scanner.Err(); err != nil {
		return ipcErrorf(ipc.IpcErrorIO, "failed to read input: %w", err)
	}
	return nil
}

func (device *Device) handleDeviceLine(key, value string) error {
	switch key {
	case "private_key":
		var sk NoisePrivateKey
		err := sk.FromMaybeZeroHex(value)
		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to set private_key: %w", err)
		}
		device.log.Verbosef("UAPI: Updating private key")
		device.SetPrivateKey(sk)

	case "listen_port":
		port, err := strconv.ParseUint(value, 10, 16)
		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to parse listen_port: %w", err)
		}

		// update port and rebind
		device.log.Verbosef("UAPI: Updating listen port")

		device.net.Lock()
		device.net.port = uint16(port)
		device.net.Unlock()

		if err := device.BindUpdate(); err != nil {
			return ipcErrorf(ipc.IpcErrorPortInUse, "failed to set listen_port: %w", err)
		}

	case "fwmark":
		mark, err := strconv.ParseUint(value, 10, 32)
		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "invalid fwmark: %w", err)
		}

		device.log.Verbosef("UAPI: Updating fwmark")
		if err := device.BindSetMark(uint32(mark)); err != nil {
			return ipcErrorf(ipc.IpcErrorPortInUse, "failed to update fwmark: %w", err)
		}

	case "replace_peers":
		if value != "true" {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to set replace_peers, invalid value: %v", value)
		}
		device.log.Verbosef("UAPI: Removing all peers")
		device.RemoveAllPeers()

	// --- AmneziaWG: junk-packet / magic-header obfuscation keys ---
	// --- (ported from amnezia-vpn/amneziawg-go; applied directly to the ---
	// --- atomic Device fields added in device.go, no staged-commit needed ---
	// --- because sagernet's handleDeviceLine already runs under ipcMutex.Lock()) ---
	case "jc":
		jc, err := strconv.ParseUint(value, 10, 32)
		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to parse jc: %w", err)
		}
		device.log.Verbosef("UAPI: Updating junk packet count")
		device.junk.count.Store(uint32(jc))

	case "jmin":
		jmin, err := strconv.ParseUint(value, 10, 32)
		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to parse jmin: %w", err)
		}
		device.log.Verbosef("UAPI: Updating junk packet min size")
		device.junk.min.Store(uint32(jmin))

	case "jmax":
		jmax, err := strconv.ParseUint(value, 10, 32)
		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to parse jmax: %w", err)
		}
		device.log.Verbosef("UAPI: Updating junk packet max size")
		device.junk.max.Store(uint32(jmax))

	case "s1":
		padding, err := strconv.ParseUint(value, 10, 16)
		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to parse s1: %w", err)
		}
		device.log.Verbosef("UAPI: Updating init packet junk size")
		device.paddings.init.Store(uint32(padding))

	case "s2":
		padding, err := strconv.ParseUint(value, 10, 16)
		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to parse s2: %w", err)
		}
		device.log.Verbosef("UAPI: Updating response packet junk size")
		device.paddings.response.Store(uint32(padding))

	case "s3":
		padding, err := strconv.ParseUint(value, 10, 16)
		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to parse s3: %w", err)
		}
		device.log.Verbosef("UAPI: Updating cookie packet junk size")
		device.paddings.cookie.Store(uint32(padding))

	case "s4":
		padding, err := strconv.ParseUint(value, 10, 16)
		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to parse s4: %w", err)
		}
		device.log.Verbosef("UAPI: Updating transport packet junk size")
		device.paddings.transport.Store(uint32(padding))

	case "h1":
		var rang UintRange
		if err := rang.FromString(value); err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to parse h1: %w", err)
		}
		device.log.Verbosef("UAPI: Updating init packet magic header")
		device.headers.init.Store(rang)

	case "h2":
		var rang UintRange
		if err := rang.FromString(value); err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to parse h2: %w", err)
		}
		device.log.Verbosef("UAPI: Updating response packet magic header")
		device.headers.response.Store(rang)

	case "h3":
		var rang UintRange
		if err := rang.FromString(value); err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to parse h3: %w", err)
		}
		device.log.Verbosef("UAPI: Updating cookie packet magic header")
		device.headers.cookie.Store(rang)

	case "h4":
		var rang UintRange
		if err := rang.FromString(value); err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to parse h4: %w", err)
		}
		device.log.Verbosef("UAPI: Updating transport packet magic header")
		device.headers.transport.Store(rang)

	case "header_protection_key":
		var key HeaderCipherKey
		if err := key.FromHex(value); err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to set header_protection_key: %w", err)
		}
		device.log.Verbosef("UAPI: Updating header protection key")
		device.headerProtection.Lock()
		device.headerProtection.key = key
		device.headerProtection.Unlock()

	case "i1":
		chain, err := newObfChain(value)
		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to parse i1: %w", err)
		}
		device.log.Verbosef("UAPI: Updating special junk packet 1")
		device.ipackets[0].Store(chain)

	case "i2":
		chain, err := newObfChain(value)
		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to parse i2: %w", err)
		}
		device.log.Verbosef("UAPI: Updating special junk packet 2")
		device.ipackets[1].Store(chain)

	case "i3":
		chain, err := newObfChain(value)
		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to parse i3: %w", err)
		}
		device.log.Verbosef("UAPI: Updating special junk packet 3")
		device.ipackets[2].Store(chain)

	case "i4":
		chain, err := newObfChain(value)
		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to parse i4: %w", err)
		}
		device.log.Verbosef("UAPI: Updating special junk packet 4")
		device.ipackets[3].Store(chain)

	case "i5":
		chain, err := newObfChain(value)
		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to parse i5: %w", err)
		}
		device.log.Verbosef("UAPI: Updating special junk packet 5")
		device.ipackets[4].Store(chain)

	default:
		return ipcErrorf(ipc.IpcErrorInvalid, "invalid UAPI device key: %v", key)
	}

	return nil
}

// An ipcSetPeer is the current state of an IPC set operation on a peer.
type ipcSetPeer struct {
	*Peer        // Peer is the current peer being operated on
	dummy   bool // dummy reports whether this peer is a temporary, placeholder peer
	created bool // new reports whether this is a newly created peer
	pkaOn   bool // pkaOn reports whether the peer had the persistent keepalive turn on
}

func (peer *ipcSetPeer) handlePostConfig() {
	if peer.Peer == nil || peer.dummy {
		return
	}
	if peer.created {
		peer.endpoint.disableRoaming = peer.device.net.brokenRoaming && peer.endpoint.val != nil
	}
	if peer.device.isUp() {
		peer.Start()
		if peer.pkaOn {
			peer.SendKeepalive()
		}
		peer.SendStagedPackets()
	}
}

func (device *Device) handlePublicKeyLine(peer *ipcSetPeer, value string) error {
	// Load/create the peer we are configuring.
	var publicKey NoisePublicKey
	err := publicKey.FromHex(value)
	if err != nil {
		return ipcErrorf(ipc.IpcErrorInvalid, "failed to get peer by public key: %w", err)
	}

	// Ignore peer with the same public key as this device.
	device.staticIdentity.RLock()
	peer.dummy = device.staticIdentity.publicKey.Equals(publicKey)
	device.staticIdentity.RUnlock()

	if peer.dummy {
		peer.Peer = &Peer{}
	} else {
		peer.Peer = device.LookupPeer(publicKey)
	}

	peer.created = peer.Peer == nil
	if peer.created {
		peer.Peer, err = device.NewPeer(publicKey)
		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to create new peer: %w", err)
		}
		device.log.Verbosef("%v - UAPI: Created", peer.Peer)
	}
	return nil
}

func (device *Device) handlePeerLine(peer *ipcSetPeer, key, value string) error {
	switch key {
	case "update_only":
		// allow disabling of creation
		if value != "true" {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to set update only, invalid value: %v", value)
		}
		if peer.created && !peer.dummy {
			device.RemovePeer(peer.handshake.remoteStatic)
			peer.Peer = &Peer{}
			peer.dummy = true
		}

	case "remove":
		// remove currently selected peer from device
		if value != "true" {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to set remove, invalid value: %v", value)
		}
		if !peer.dummy {
			device.log.Verbosef("%v - UAPI: Removing", peer.Peer)
			device.RemovePeer(peer.handshake.remoteStatic)
		}
		peer.Peer = &Peer{}
		peer.dummy = true

	case "preshared_key":
		device.log.Verbosef("%v - UAPI: Updating preshared key", peer.Peer)

		peer.handshake.mutex.Lock()
		err := peer.handshake.presharedKey.FromHex(value)
		peer.handshake.mutex.Unlock()

		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to set preshared key: %w", err)
		}

	case "endpoint":
		device.log.Verbosef("%v - UAPI: Updating endpoint", peer.Peer)
		endpoint, err := device.net.bind.ParseEndpoint(value)
		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to set endpoint %v: %w", value, err)
		}
		peer.endpoint.Lock()
		defer peer.endpoint.Unlock()
		peer.endpoint.val = endpoint

	case "persistent_keepalive_interval":
		device.log.Verbosef("%v - UAPI: Updating persistent keepalive interval", peer.Peer)

		secs, err := strconv.ParseUint(value, 10, 16)
		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to set persistent keepalive interval: %w", err)
		}

		old := peer.persistentKeepaliveInterval.Swap(uint32(secs))

		// Send immediate keepalive if we're turning it on and before it wasn't on.
		peer.pkaOn = old == 0 && secs != 0

	case "replace_allowed_ips":
		device.log.Verbosef("%v - UAPI: Removing all allowedips", peer.Peer)
		if value != "true" {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to replace allowedips, invalid value: %v", value)
		}
		if peer.dummy {
			return nil
		}
		device.allowedips.RemoveByPeer(peer.Peer)

	case "allowed_ip":
		add := true
		verb := "Adding"
		if len(value) > 0 && value[0] == '-' {
			add = false
			verb = "Removing"
			value = value[1:]
		}
		device.log.Verbosef("%v - UAPI: %s allowedip", peer.Peer, verb)
		prefix, err := netip.ParsePrefix(value)
		if err != nil {
			return ipcErrorf(ipc.IpcErrorInvalid, "failed to set allowed ip: %w", err)
		}
		if peer.dummy {
			return nil
		}
		if add {
			device.allowedips.Insert(prefix, peer.Peer)
		} else {
			device.allowedips.Remove(prefix, peer.Peer)
		}

	case "protocol_version":
		if value != "1" {
			return ipcErrorf(ipc.IpcErrorInvalid, "invalid protocol version: %v", value)
		}

	default:
		return ipcErrorf(ipc.IpcErrorInvalid, "invalid UAPI peer key: %v", key)
	}

	return nil
}

func (device *Device) IpcGet() (string, error) {
	buf := new(strings.Builder)
	if err := device.IpcGetOperation(buf); err != nil {
		return "", err
	}
	return buf.String(), nil
}

func (device *Device) IpcSet(uapiConf string) error {
	return device.IpcSetOperation(strings.NewReader(uapiConf))
}

func (device *Device) IpcHandle(socket net.Conn) {
	defer socket.Close()

	buffered := func(s io.ReadWriter) *bufio.ReadWriter {
		reader := bufio.NewReader(s)
		writer := bufio.NewWriter(s)
		return bufio.NewReadWriter(reader, writer)
	}(socket)

	for {
		op, err := buffered.ReadString('\n')
		if err != nil {
			return
		}

		// handle operation
		switch op {
		case "set=1\n":
			err = device.IpcSetOperation(buffered.Reader)
		case "get=1\n":
			var nextByte byte
			nextByte, err = buffered.ReadByte()
			if err != nil {
				return
			}
			if nextByte != '\n' {
				err = ipcErrorf(ipc.IpcErrorInvalid, "trailing character in UAPI get: %q", nextByte)
				break
			}
			err = device.IpcGetOperation(buffered.Writer)
		default:
			device.log.Errorf("invalid UAPI operation: %v", op)
			return
		}

		// write status
		var status *IPCError
		if err != nil && !errors.As(err, &status) {
			// shouldn't happen
			status = ipcErrorf(ipc.IpcErrorUnknown, "other UAPI error: %w", err)
		}
		if status != nil {
			device.log.Errorf("%v", status)
			fmt.Fprintf(buffered, "errno=%d\n\n", status.ErrorCode())
		} else {
			fmt.Fprintf(buffered, "errno=0\n\n")
		}
		buffered.Flush()
	}
}
