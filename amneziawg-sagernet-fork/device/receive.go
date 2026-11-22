/* SPDX-License-Identifier: MIT
 *
 * Copyright (C) 2017-2025 WireGuard LLC. All Rights Reserved.
 */

package device

import (
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sync"
	"time"

	"github.com/sagernet/wireguard-go/conn"
	"golang.org/x/crypto/chacha20poly1305"
	"golang.org/x/net/ipv4"
	"golang.org/x/net/ipv6"
)

type QueueHandshakeElement struct {
	msgType  uint32
	packet   []byte
	endpoint conn.Endpoint
	buffer   *[MaxMessageSize]byte
}

type QueueInboundElement struct {
	buffer   *[MaxMessageSize]byte
	packet   []byte
	counter  uint64
	keypair  *Keypair
	endpoint conn.Endpoint
}

type QueueInboundElementsContainer struct {
	// filling is a one-shot barrier signaling decryption→receive
	// handoff. RoutineReceiveIncoming calls Add(1) before sending the
	// container down the decryption and inbound queues; RoutineDecryption
	// calls Done after decrypting; RoutineSequentialReceiver calls Wait
	// before reading the decrypted packets.
	filling sync.WaitGroup
	elems   []*QueueInboundElement
}

// clearPointers clears elem fields that contain pointers.
// This makes the garbage collector's life easier and
// avoids accidentally keeping other objects around unnecessarily.
// It also reduces the possible collateral damage from use-after-free bugs.
func (elem *QueueInboundElement) clearPointers() {
	elem.buffer = nil
	elem.packet = nil
	elem.keypair = nil
	elem.endpoint = nil
}

/* Called when a new authenticated message has been received
 *
 * NOTE: Not thread safe, but called by sequential receiver!
 */
func (peer *Peer) keepKeyFreshReceiving() {
	if peer.timers.sentLastMinuteHandshake.Load() {
		return
	}
	keypair := peer.keypairs.Current()
	if keypair != nil && keypair.isInitiator && sinceIncludingSleep(keypair.created) > (RejectAfterTime-KeepaliveTimeout-RekeyTimeout) {
		peer.timers.sentLastMinuteHandshake.Store(true)
		peer.SendHandshakeInitiation(false)
	}
}

/* Receives incoming datagrams for the device
 *
 * Every time the bind is updated a new routine is started for
 * IPv4 and IPv6 (separately)
 */
func (device *Device) RoutineReceiveIncoming(maxBatchSize int, recv conn.ReceiveFunc) {
	recvName := recv.PrettyName()
	defer func() {
		device.log.Verbosef("Routine: receive incoming %s - stopped", recvName)
		device.queue.decryption.wg.Done()
		device.queue.handshake.wg.Done()
		device.net.stopping.Done()
	}()

	device.log.Verbosef("Routine: receive incoming %s - started", recvName)

	// receive datagrams until conn is closed

	var (
		bufsArrs    = make([]*[MaxMessageSize]byte, maxBatchSize)
		bufs        = make([][]byte, maxBatchSize)
		err         error
		sizes       = make([]int, maxBatchSize)
		count       int
		endpoints   = make([]conn.Endpoint, maxBatchSize)
		deathSpiral int
		elemsByPeer = make(map[*Peer]*QueueInboundElementsContainer, maxBatchSize)
	)

	for i := range bufsArrs {
		bufsArrs[i] = device.GetMessageBuffer()
		bufs[i] = bufsArrs[i][:]
	}

	defer func() {
		for i := 0; i < maxBatchSize; i++ {
			if bufsArrs[i] != nil {
				device.PutMessageBuffer(bufsArrs[i])
			}
		}
	}()

	for {
		count, err = recv(bufs, sizes, endpoints)
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			device.log.Verbosef("Failed to receive %s packet: %v", recvName, err)
			if errors.Is(err, conn.ErrRebindRequired) {
				device.scheduleBindUpdate()
				return
			}
			if neterr, ok := err.(net.Error); ok && !neterr.Temporary() {
				return
			}
			if deathSpiral < 10 {
				deathSpiral++
				time.Sleep(time.Second / 3)
				continue
			}
			return
		}
		deathSpiral = 0

		// handle each packet in the batch
		for i, size := range sizes[:count] {
			if size < MinMessageSize {
				continue
			}

			// check size of packet

			packet := bufsArrs[i][:size]

			// --- AmneziaWG: optional header-protection XOR applied to a ---
			// --- 4-byte "type hash" before it's used to recognize the ---
			// --- obfuscated message type below. HeaderProtectionCipher ---
			// --- returns (nil, nil) unless an h-key was configured (not yet ---
			// --- exposed via UAPI in this merge, see noise-protocol.go), so ---
			// --- typeHash stays all-zero and this is a pure no-op for every ---
			// --- peer today; it is wired up now so a later pass that adds ---
			// --- the UAPI key does not need to touch receive.go again. ---
			var typeHashBuf [4]byte
			typeHash := typeHashBuf[:]
			cip, cerr := device.HeaderProtectionCipher(packet[:min(HeaderCipherNonceSize, len(packet))])
			if cerr != nil {
				device.log.Errorf("Failed to initialize header cipher")
				continue
			}
			if cip != nil {
				cip.XORKeyStream(typeHash, typeHash)
			}

			// --- AmneziaWG: recognize the message's real type + any ---
			// --- leading padding (s1-s4) from its (possibly obfuscated, ---
			// --- possibly junk-sized) magic header (h1-h4), instead of ---
			// --- always reading a fixed message-type byte at offset 0. ---
			// --- Falls through to "unknown type" (handled below exactly ---
			// --- like upstream's invalid-mac1/bad-size rejects) for any ---
			// --- packet that doesn't match a configured header range — ---
			// --- this is also how junk packets sent by JunkPackets() in ---
			// --- send.go get silently dropped by a receiving peer. ---
			msgSize, msgType, padding := device.DeterminePacketTypeAndPadding(packet, typeHash)
			if msgType == MessageUnknownType {
				continue
			}
			packet = packet[padding:]
			if msgType != MessageTransportType {
				packet = packet[:msgSize]
			}
			if cip != nil {
				applyHash(packet[:4], packet[:4], typeHash)
			}

			switch msgType {

			// check if transport

			case MessageTransportType:

				// check size

				if len(packet) < MessageTransportSize {
					continue
				}
				if cip != nil {
					cip.XORKeyStream(packet[4:MessageTransportHeaderSize], packet[4:MessageTransportHeaderSize])
				}

				// lookup key pair

				receiver := binary.LittleEndian.Uint32(
					packet[MessageTransportOffsetReceiver:MessageTransportOffsetCounter],
				)
				value := device.indexTable.Lookup(receiver)
				keypair := value.keypair
				if keypair == nil {
					continue
				}

				// check keypair expiry

				if sinceIncludingSleep(keypair.created) > RejectAfterTime {
					continue
				}

				// create work element
				peer := value.peer
				elem := device.GetInboundElement()
				elem.packet = packet
				elem.buffer = bufsArrs[i]
				elem.keypair = keypair
				elem.endpoint = endpoints[i]
				elem.counter = 0

				elemsForPeer, ok := elemsByPeer[peer]
				if !ok {
					elemsForPeer = device.GetInboundElementsContainer()
					elemsByPeer[peer] = elemsForPeer
				}
				elemsForPeer.elems = append(elemsForPeer.elems, elem)
				bufsArrs[i] = device.GetMessageBuffer()
				bufs[i] = bufsArrs[i][:]
				continue

			// otherwise it is a fixed size & handshake related packet

			case MessageInitiationType:
				if len(packet) != MessageInitiationSize {
					continue
				}
				if cip != nil {
					cip.XORKeyStream(packet[4:MessageInitiationSize], packet[4:MessageInitiationSize])
				}

			case MessageResponseType:
				if len(packet) != MessageResponseSize {
					continue
				}
				if cip != nil {
					cip.XORKeyStream(packet[4:MessageResponseSize], packet[4:MessageResponseSize])
				}

			case MessageCookieReplyType:
				if len(packet) != MessageCookieReplySize {
					continue
				}
				if cip != nil {
					cip.XORKeyStream(packet[4:MessageCookieReplySize], packet[4:MessageCookieReplySize])
				}

			default:
				device.log.Verbosef("Received message with unknown type")
				continue
			}

			select {
			case device.queue.handshake.c <- QueueHandshakeElement{
				msgType:  msgType,
				buffer:   bufsArrs[i],
				packet:   packet,
				endpoint: endpoints[i],
			}:
				bufsArrs[i] = device.GetMessageBuffer()
				bufs[i] = bufsArrs[i][:]
			default:
			}
		}
		for peer, elemsContainer := range elemsByPeer {
			if peer.isRunning.Load() {
				elemsContainer.filling.Add(1)
				peer.queue.inbound.c <- elemsContainer
				device.queue.decryption.c <- elemsContainer
			} else {
				for _, elem := range elemsContainer.elems {
					device.PutMessageBuffer(elem.buffer)
					device.PutInboundElement(elem)
				}
				device.PutInboundElementsContainer(elemsContainer)
			}
			delete(elemsByPeer, peer)
		}
	}
}

func (device *Device) RoutineDecryption(id int) {
	var nonce [chacha20poly1305.NonceSize]byte

	defer device.log.Verbosef("Routine: decryption worker %d - stopped", id)
	device.log.Verbosef("Routine: decryption worker %d - started", id)

	for elemsContainer := range device.queue.decryption.c {
		for _, elem := range elemsContainer.elems {
			// split message into fields
			counter := elem.packet[MessageTransportOffsetCounter:MessageTransportOffsetContent]
			content := elem.packet[MessageTransportOffsetContent:]

			// decrypt and release to consumer
			var err error
			elem.counter = binary.LittleEndian.Uint64(counter)
			// copy counter to nonce
			binary.LittleEndian.PutUint64(nonce[0x4:0xc], elem.counter)
			elem.packet, err = elem.keypair.receive.Open(
				content[:0],
				nonce[:],
				content,
				nil,
			)
			if err != nil {
				elem.packet = nil
			}
		}
		elemsContainer.filling.Done()
	}
}

/* Handles incoming packets related to handshake
 */
func (device *Device) RoutineHandshake(id int) {
	defer func() {
		device.log.Verbosef("Routine: handshake worker %d - stopped", id)
		device.queue.encryption.wg.Done()
	}()
	device.log.Verbosef("Routine: handshake worker %d - started", id)

	for elem := range device.queue.handshake.c {

		// handle cookie fields and ratelimiting

		switch elem.msgType {

		case MessageCookieReplyType:

			// unmarshal packet

			var reply MessageCookieReply
			err := reply.unmarshal(elem.packet)
			if err != nil {
				device.log.Verbosef("Failed to decode cookie reply")
				goto skip
			}

			// lookup peer from index

			entry := device.indexTable.Lookup(reply.Receiver)

			if entry.peer == nil {
				goto skip
			}

			// consume reply

			if peer := entry.peer; peer.isRunning.Load() {
				device.log.Verbosef("Receiving cookie response from %s", elem.endpoint.DstToString())
				if !peer.cookieGenerator.ConsumeReply(&reply) {
					device.log.Verbosef("Could not decrypt invalid cookie response")
				}
			}

			goto skip

		case MessageInitiationType, MessageResponseType:

			// check mac fields and maybe ratelimit

			if !device.cookieChecker.CheckMAC1(elem.packet) {
				device.log.Verbosef("Received packet with invalid mac1")
				goto skip
			}

			// endpoints destination address is the source of the datagram

			if device.IsUnderLoad() {

				// verify MAC2 field

				if !device.cookieChecker.CheckMAC2(elem.packet, elem.endpoint.DstToBytes()) {
					device.SendHandshakeCookie(&elem)
					goto skip
				}

				// check ratelimiter

				if !device.rate.limiter.Allow(elem.endpoint.DstIP()) {
					goto skip
				}
			}

		default:
			device.log.Errorf("Invalid packet ended up in the handshake queue")
			goto skip
		}

		// handle handshake initiation/response content

		switch elem.msgType {
		case MessageInitiationType:

			// unmarshal

			var msg MessageInitiation
			err := msg.unmarshal(elem.packet)
			if err != nil {
				device.log.Errorf("Failed to decode initiation message")
				goto skip
			}

			// --- AmneziaWG: have to reassign msg.Type back to the ---
			// --- standard constant after unmarshal, because elem.packet's ---
			// --- first bytes may hold an obfuscated (ranged) magic-header ---
			// --- value picked by the sender's PickOne(), not literally ---
			// --- MessageInitiationType. Downstream code (ConsumeMessageInitiation ---
			// --- and everything it calls) expects the plain constant. ---
			msg.Type = elem.msgType

			// consume initiation

			peer := device.ConsumeMessageInitiation(&msg, elem.endpoint)
			if peer == nil {
				device.log.Verbosef("Received invalid initiation message from %s", elem.endpoint.DstToString())
				goto skip
			}

			// update timers

			peer.timersAnyAuthenticatedPacketTraversal()
			peer.timersAnyAuthenticatedPacketReceived()

			// update endpoint
			peer.SetEndpointFromPacket(elem.endpoint)

			device.log.Verbosef("%v - Received handshake initiation", peer)
			peer.rxBytes.Add(uint64(len(elem.packet)))

			peer.SendHandshakeResponse()

		case MessageResponseType:

			// unmarshal

			var msg MessageResponse
			err := msg.unmarshal(elem.packet)
			if err != nil {
				device.log.Errorf("Failed to decode response message")
				goto skip
			}

			// --- AmneziaWG: same reassignment as MessageInitiation above. ---
			msg.Type = elem.msgType

			// consume response

			peer := device.ConsumeMessageResponse(&msg)
			if peer == nil {
				device.log.Verbosef("Received invalid response message from %s", elem.endpoint.DstToString())
				goto skip
			}

			// update endpoint
			peer.SetEndpointFromPacket(elem.endpoint)

			device.log.Verbosef("%v - Received handshake response", peer)
			peer.rxBytes.Add(uint64(len(elem.packet)))

			// update timers

			peer.timersAnyAuthenticatedPacketTraversal()
			peer.timersAnyAuthenticatedPacketReceived()

			// derive keypair

			err = peer.BeginSymmetricSession()
			if err != nil {
				device.log.Errorf("%v - Failed to derive keypair: %v", peer, err)
				goto skip
			}

			peer.timersSessionDerived()
			peer.timersHandshakeComplete()
			peer.SendPriorityMessage()
			peer.SendKeepalive()
		}
	skip:
		device.PutMessageBuffer(elem.buffer)
	}
}

func (peer *Peer) RoutineSequentialReceiver(maxBatchSize int) {
	device := peer.device
	defer func() {
		device.log.Verbosef("%v - Routine: sequential receiver - stopped", peer)
		peer.stopping.Done()
	}()
	device.log.Verbosef("%v - Routine: sequential receiver - started", peer)

	bufs := make([][]byte, 0, maxBatchSize)

	for elemsContainer := range peer.queue.inbound.c {
		if elemsContainer == nil {
			return
		}
		peer.processInboundContainer(elemsContainer, bufs[:0])
	}
}

// processInboundContainer waits for the decryption routine to finish
// filling elemsContainer, then writes the valid packets to the TUN
// device and returns the container to the pool.
//
// scratch is a length-0 slice used to assemble the per-packet buffers
// passed to tun.device.Write; its backing array is reused across calls.
func (peer *Peer) processInboundContainer(elemsContainer *QueueInboundElementsContainer, scratch [][]byte) {
	// Invariants from RoutineSequentialReceiver; all should be unreachable.
	if len(scratch) != 0 || cap(scratch) == 0 {
		panic(fmt.Sprintf("processInboundContainer: scratch must be empty with non-zero cap; got len=%d cap=%d",
			len(scratch), cap(scratch)))
	}
	if cap(scratch) < len(elemsContainer.elems) {
		panic(fmt.Sprintf("processInboundContainer: scratch cap %d < elems %d",
			cap(scratch), len(elemsContainer.elems)))
	}

	device := peer.device
	defer device.PutInboundElementsContainer(elemsContainer)

	// Wait for RoutineDecryption to finish filling the container. After
	// Wait returns we have happens-before with that goroutine and are the
	// sole owner of the container until Put hands it back to the pool.
	elemsContainer.filling.Wait()
	elems := elemsContainer.elems

	validTailPacket := -1
	dataPacketReceived := false
	rxBytesLen := uint64(0)
	for i, elem := range elems {
		if elem.packet == nil {
			// decryption failed
			continue
		}

		if !elem.keypair.replayFilter.ValidateCounter(elem.counter, RejectAfterMessages) {
			continue
		}

		validTailPacket = i
		if peer.ReceivedWithKeypair(elem.keypair) {
			peer.SetEndpointFromPacket(elem.endpoint)
			peer.timersHandshakeComplete()
			peer.SendPriorityMessage()
			peer.SendStagedPackets()
		}
		if ep, ok := elem.endpoint.(conn.PeerAwareEndpoint); ok {
			ep.FromPeer(peer.handshake.remoteStatic)
		}
		rxBytesLen += uint64(len(elem.packet) + MinMessageSize)

		if len(elem.packet) == 0 {
			device.log.Verbosef("%v - Receiving keepalive packet", peer)
			continue
		}
		dataPacketReceived = true

		switch elem.packet[0] >> 4 {
		case 4:
			if len(elem.packet) < ipv4.HeaderLen {
				continue
			}
			field := elem.packet[IPv4offsetTotalLength : IPv4offsetTotalLength+2]
			length := binary.BigEndian.Uint16(field)
			if int(length) > len(elem.packet) || int(length) < ipv4.HeaderLen {
				continue
			}
			elem.packet = elem.packet[:length]
			src := elem.packet[IPv4offsetSrc : IPv4offsetSrc+net.IPv4len]
			srcAddr, _ := netip.AddrFromSlice(src)
			if !peer.AllowedPeerSourceIP(srcAddr) {
				device.log.Verbosef("IPv4 packet with disallowed source address from %v", peer)
				continue
			}

		case 6:
			if len(elem.packet) < ipv6.HeaderLen {
				continue
			}
			field := elem.packet[IPv6offsetPayloadLength : IPv6offsetPayloadLength+2]
			length := binary.BigEndian.Uint16(field)
			length += ipv6.HeaderLen
			if int(length) > len(elem.packet) {
				continue
			}
			elem.packet = elem.packet[:length]
			src := elem.packet[IPv6offsetSrc : IPv6offsetSrc+net.IPv6len]
			srcAddr, _ := netip.AddrFromSlice(src)
			if !peer.AllowedPeerSourceIP(srcAddr) {
				device.log.Verbosef("IPv6 packet with disallowed source address from %v", peer)
				continue
			}

		default:
			device.log.Verbosef("Packet with invalid IP version from %v", peer)
			continue
		}

		scratch = append(scratch, elem.buffer[:MessageTransportOffsetContent+len(elem.packet)])
	}

	peer.rxBytes.Add(rxBytesLen)
	if validTailPacket >= 0 {
		peer.SetEndpointFromPacket(elems[validTailPacket].endpoint)
		peer.keepKeyFreshReceiving()
		peer.timersAnyAuthenticatedPacketTraversal()
		peer.timersAnyAuthenticatedPacketReceived()
	}
	if dataPacketReceived {
		peer.timersDataReceived()
	}
	if len(scratch) > 0 {
		_, err := device.tun.device.Write(scratch, MessageTransportOffsetContent)
		if err != nil && !device.isClosed() {
			device.log.Errorf("Failed to write packets to TUN device: %v", err)
		}
	}
	for _, elem := range elems {
		device.PutMessageBuffer(elem.buffer)
		device.PutInboundElement(elem)
	}
}

// --- AmneziaWG: header-protection helper + message-type/padding recognition ---
// --- (ported verbatim from amnezia-vpn/amneziawg-go). ---

// applyHash XORs src with hash into dst, byte by byte. Used to unprotect
// (or, on the sending side via the same XOR cipher, protect) the leading
// 4 bytes of a packet that make up its magic-header type field. dst and src
// may overlap in the same underlying array (the usual call pattern here is
// applyHash(packet[:4], packet[:4], typeHash)).
func applyHash(dst, src, hash []byte) {
	for i := range len(dst) {
		dst[i] = src[i] ^ hash[i]
	}
}

// DeterminePacketTypeAndPadding inspects a raw received packet and figures
// out which AmneziaWG magic-header range (if any) its leading padding+4-byte
// type field matches, trying init/response/cookie/transport in that order.
// Returns (0, MessageUnknownType, 0) if nothing matches, which the caller
// treats as "drop this packet" — this is also how the random junk packets
// sent by JunkPackets() (see noise-protocol.go) get silently discarded by a
// receiving peer, since junk packets are just random bytes that are
// vanishingly unlikely to land inside any configured header range.
//
// When h1-h4 were never configured, device.headers.* still hold their
// NewDevice defaults (the plain MessageInitiationType/.../MessageTransportType
// constants) and paddings.* are all zero, so this reduces to exactly the
// upstream fixed-offset, fixed-size behavior.
func (device *Device) DeterminePacketTypeAndPadding(packet []byte, typeHash []byte) (int, uint32, uint32) {
	var headerBytes [4]byte
	var padding uint32
	var header UintRange
	var expectedSize int

	size := len(packet)

	padding = device.paddings.init.Load()
	header = device.headers.init.Load()
	expectedSize = int(padding) + MessageInitiationSize

	if size == expectedSize && int(padding)+4 <= size {
		applyHash(headerBytes[:], packet[padding:padding+4], typeHash)
		if header.Contains(binary.LittleEndian.Uint32(headerBytes[:])) {
			return MessageInitiationSize, MessageInitiationType, padding
		}
	}

	padding = device.paddings.response.Load()
	header = device.headers.response.Load()
	expectedSize = int(padding) + MessageResponseSize

	if size == expectedSize && int(padding)+4 <= size {
		applyHash(headerBytes[:], packet[padding:padding+4], typeHash)
		if header.Contains(binary.LittleEndian.Uint32(headerBytes[:])) {
			return MessageResponseSize, MessageResponseType, padding
		}
	}

	padding = device.paddings.cookie.Load()
	header = device.headers.cookie.Load()
	expectedSize = int(padding) + MessageCookieReplySize

	if size == expectedSize && int(padding)+4 <= size {
		applyHash(headerBytes[:], packet[padding:padding+4], typeHash)
		if header.Contains(binary.LittleEndian.Uint32(headerBytes[:])) {
			return MessageCookieReplySize, MessageCookieReplyType, padding
		}
	}

	padding = device.paddings.transport.Load()
	header = device.headers.transport.Load()
	expectedSize = int(padding) + MessageTransportSize

	if size >= expectedSize && int(padding)+4 <= size {
		applyHash(headerBytes[:], packet[padding:padding+4], typeHash)
		if header.Contains(binary.LittleEndian.Uint32(headerBytes[:])) {
			return MessageTransportSize, MessageTransportType, padding
		}
	}

	return 0, MessageUnknownType, 0
}
