package option

import (
	"net/netip"

	"github.com/sagernet/sing/common/json/badoption"
)

type WireGuardEndpointOptions struct {
	System       bool                             `json:"system,omitempty"`
	Name         string                           `json:"name,omitempty"`
	MTU          uint32                           `json:"mtu,omitempty"`
	Address      badoption.Listable[netip.Prefix] `json:"address"`
	PrivateKey   string                           `json:"private_key"`
	ListenPort   uint16                           `json:"listen_port,omitempty"`
	Peers        []WireGuardPeer                  `json:"peers,omitempty"`
	UDPTimeout   badoption.Duration               `json:"udp_timeout,omitempty"`
	UDPMapping   UDPNATBehavior                   `json:"udp_mapping,omitempty"`
	UDPFiltering UDPNATBehavior                   `json:"udp_filtering,omitempty"`
	UDPNATMax    uint32                           `json:"udp_nat_max,omitempty"`
	Workers      int                              `json:"workers,omitempty"`
	OnDemand     bool                             `json:"on_demand,omitempty"`

	// AmneziaWG obfuscation (optional; all omitempty, so an ordinary
	// WireGuard config without these fields behaves byte-for-byte as
	// before). These are interface-level (not per-peer) settings, matching
	// the AmneziaWG [Interface] config-file section and the jc/jmin/jmax/
	// s1-s4/h1-h4/header_protection_key UAPI keys merged into
	// sagernet/wireguard-go (see transport/wireguard/endpoint.go).
	JunkPacketCount            int    `json:"jc,omitempty"`
	JunkPacketMinSize          int    `json:"jmin,omitempty"`
	JunkPacketMaxSize          int    `json:"jmax,omitempty"`
	InitPacketJunkSize         int    `json:"s1,omitempty"`
	ResponsePacketJunkSize     int    `json:"s2,omitempty"`
	CookiePacketJunkSize       int    `json:"s3,omitempty"`
	TransportPacketJunkSize    int    `json:"s4,omitempty"`
	InitPacketMagicHeader      string `json:"h1,omitempty"`
	ResponsePacketMagicHeader  string `json:"h2,omitempty"`
	CookiePacketMagicHeader    string `json:"h3,omitempty"`
	TransportPacketMagicHeader string `json:"h4,omitempty"`
	HeaderProtectionKey        string `json:"header_protection_key,omitempty"`

	DialerOptions
}

type WireGuardPeer struct {
	Address                     string                       `json:"address,omitempty"`
	Port                        uint16                       `json:"port,omitempty"`
	PublicKey                   string                       `json:"public_key,omitempty"`
	PreSharedKey                string                       `json:"pre_shared_key,omitempty"`
	AllowedIPs                  LegacyListable[netip.Prefix] `json:"allowed_ips,omitempty"`
	PersistentKeepaliveInterval uint16                       `json:"persistent_keepalive_interval,omitempty"`
	Reserved                    []uint8                      `json:"reserved,omitempty"`
}
