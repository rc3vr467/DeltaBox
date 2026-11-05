package libcore

import (
	"context"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/adapter/service"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
)

// Registries are provided by sing-box's own `include` package (feature set is
// controlled by build tags: with_quic, with_wireguard, with_utls, with_clash_api, ...).

func nekoboxAndroidInboundRegistry() *inbound.Registry {
	return include.InboundRegistry()
}

func nekoboxAndroidOutboundRegistry() *outbound.Registry {
	return include.OutboundRegistry()
}

func nekoboxAndroidEndpointRegistry() *endpoint.Registry {
	return include.EndpointRegistry()
}

func nekoboxAndroidDNSTransportRegistry(localTransport LocalDNSTransport) *dns.TransportRegistry {
	registry := include.DNSTransportRegistry()

	if localTransport != nil {
		// Replace the built-in "local" transport with the Android platform one.
		dns.RegisterTransport(registry, "local", func(ctx context.Context, logger log.ContextLogger, tag string, options option.LocalDNSServerOptions) (adapter.DNSTransport, error) {
			return newPlatformTransport(localTransport, tag, options), nil
		})
	}

	return registry
}

func nekoboxAndroidServiceRegistry() *service.Registry {
	return include.ServiceRegistry()
}

func nekoboxAndroidCertificateProviderRegistry() *certificate.Registry {
	return include.CertificateProviderRegistry()
}
