// Package boxapi is a thin compatibility layer between the Android libcore
// (DeltaBox / NekoBox) and upstream sing-box. It replaces the package of the
// same name that existed in the MatsuriDayo sing-box fork.
package boxapi

import (
	"context"
	"net"
	"net/http"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/experimental/v2rayapi"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
)

// SbV2rayServer exposes the v2ray-style traffic statistics service without gRPC.
type SbV2rayServer struct {
	statsService *v2rayapi.StatsService
}

func NewSbV2rayServer(options option.V2RayStatsServiceOptions) *SbV2rayServer {
	options.Enabled = true
	return &SbV2rayServer{statsService: v2rayapi.NewStatsService(options)}
}

// StatsService returns the connection tracker that must be appended to the router.
func (s *SbV2rayServer) StatsService() adapter.ConnectionTracker {
	return s.statsService
}

// QueryStats returns the counter value for name and resets it (delta semantics,
// matching what TrafficUpdater on the Java side expects).
func (s *SbV2rayServer) QueryStats(name string) int64 {
	if s == nil || s.statsService == nil {
		return 0
	}
	resp, err := s.statsService.GetStats(context.Background(), &v2rayapi.GetStatsRequest{
		Name:   name,
		Reset_: true,
	})
	if err != nil || resp == nil || resp.Stat == nil {
		return 0
	}
	return resp.Stat.Value
}

// CreateProxyHttpClient builds an http.Client that dials through the default
// outbound of b. With b == nil the client connects directly.
func CreateProxyHttpClient(b *box.Box, tracker adapter.ConnectionTracker) *http.Client {
	transport := &http.Transport{
		TLSHandshakeTimeout:   time.Second * 3,
		DisableKeepAlives:     true,
		ResponseHeaderTimeout: time.Second * 30,
	}
	if b != nil {
		transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			outbound := b.Outbound().Default()
			conn, err := outbound.DialContext(ctx, network, M.ParseSocksaddr(addr))
			if err != nil {
				return nil, err
			}
			if tracker != nil {
				conn = tracker.RoutedConnection(ctx, conn, adapter.InboundContext{}, nil, outbound)
			}
			return conn, nil
		}
	} else {
		transport.DialContext = (&net.Dialer{Timeout: time.Second * 5}).DialContext
	}
	return &http.Client{Transport: transport}
}
