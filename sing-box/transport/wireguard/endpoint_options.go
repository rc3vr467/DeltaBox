package wireguard

import (
	"context"
	"net/netip"
	"time"

	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type EndpointOptions struct {
	Context      context.Context
	Logger       logger.ContextLogger
	System       bool
	Handler      tun.Handler
	UDPTimeout   time.Duration
	ICMPTimeout  time.Duration
	UDPMapping   tun.NATMapping
	UDPFiltering tun.NATFiltering
	UDPNATMax    uint32

	InterfaceFinder   control.InterfaceFinder
	EgressPoolOptions tun.UDPEgressPoolOptions
	Dialer            N.Dialer
	CreateDialer      func(interfaceName string) N.Dialer
	Tag               string
	Name              string
	MTU               uint32
	Address           []netip.Prefix
	PrivateKey        string
	ListenPort        uint16
	ResolvePeer       func(domain string) ([]netip.Addr, error)
	Peers             []PeerOptions
	Workers           int

	// AmneziaWG obfuscation (optional, see option.WireGuardEndpointOptions
	// for the corresponding JSON config fields).
	JunkPacketCount            int
	JunkPacketMinSize          int
	JunkPacketMaxSize          int
	InitPacketJunkSize         int
	ResponsePacketJunkSize     int
	CookiePacketJunkSize       int
	TransportPacketJunkSize    int
	InitPacketMagicHeader      string
	ResponsePacketMagicHeader  string
	CookiePacketMagicHeader    string
	TransportPacketMagicHeader string
	HeaderProtectionKey        string

	// AmneziaWG i1-i5 (see option.WireGuardEndpointOptions).
	SpecialJunkPacket1 string
	SpecialJunkPacket2 string
	SpecialJunkPacket3 string
	SpecialJunkPacket4 string
	SpecialJunkPacket5 string
}

type PeerOptions struct {
	Endpoint                    M.Socksaddr
	PublicKey                   string
	PreSharedKey                string
	AllowedIPs                  []netip.Prefix
	PersistentKeepaliveInterval uint16
	Reserved                    []uint8
}
