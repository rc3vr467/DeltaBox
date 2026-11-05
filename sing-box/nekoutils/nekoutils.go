// Package nekoutils holds hook variables that the Android libcore layer
// (DeltaBox / NekoBox) assigns at start-up. The sing-box core calls them when
// non-nil. This replaces the MatsuriDayo fork's package of the same name.
package nekoutils

import "github.com/sagernet/sing-box/option"

var (
	// GetGeoIPHeadlessRules returns rules for a country code (e.g. "cn").
	GetGeoIPHeadlessRules func(name string) ([]option.HeadlessRule, error)
	// GetGeoSiteHeadlessRules returns rules for a geosite code (e.g. "category-ads-all").
	GetGeoSiteHeadlessRules func(name string) ([]option.HeadlessRule, error)
	// Selector_OnProxySelected is invoked whenever a selector outbound changes its selection.
	Selector_OnProxySelected func(selectorTag string, tag string)
)
