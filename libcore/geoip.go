package libcore

import (
	"encoding/json"
	"fmt"
	"net"
	"path/filepath"
	"strings"

	"github.com/oschwald/maxminddb-golang"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

type geoip struct {
	geoipReader *maxminddb.Reader
}

func (g *geoip) Open(path string) error {
	geoipReader, err := maxminddb.Open(path)
	g.geoipReader = geoipReader
	return err
}

func (g *geoip) Rules(countryCode string) ([]option.HeadlessRule, error) {
	networks := g.geoipReader.Networks(maxminddb.SkipAliasedNetworks)
	countryMap := make(map[string][]*net.IPNet)
	var (
		ipNet           *net.IPNet
		nextCountryCode string
		err             error
	)
	for networks.Next() {
		ipNet, err = networks.Network(&nextCountryCode)
		if err != nil {
			return nil, fmt.Errorf("failed to get network: %w", err)
		}
		countryMap[nextCountryCode] = append(countryMap[nextCountryCode], ipNet)
	}

	ipNets := countryMap[strings.ToLower(countryCode)]

	if len(ipNets) == 0 {
		return nil, fmt.Errorf("no networks found for country code: %s", countryCode)
	}

	var headlessRule option.DefaultHeadlessRule
	cidrs := make([]string, 0, len(ipNets))
	for _, cidr := range ipNets {
		cidrs = append(cidrs, cidr.String())
	}
	// IPCIDR is a Listable of *badoption.Prefixable in recent sing-box; decode from strings.
	cidrJSON, err := json.Marshal(cidrs)
	if err != nil {
		return nil, err
	}
	if err = json.Unmarshal(cidrJSON, &headlessRule.IPCIDR); err != nil {
		return nil, fmt.Errorf("parse cidr list: %w", err)
	}

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}

func init() {
	nekoutils.GetGeoIPHeadlessRules = func(name string) ([]option.HeadlessRule, error) {
		g := new(geoip)
		if err := g.Open(filepath.Join(externalAssetsPath, "geoip.db")); err != nil {
			return nil, err
		}
		defer g.geoipReader.Close()
		return g.Rules(name)
	}
}
