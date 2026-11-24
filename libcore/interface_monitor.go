package libcore

import (
	"sync"

	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/x/list"
)

// Previously interfaceMonitorStub.DefaultInterface() always returned nil ("wtf").
// sing-box's router treats a nil default interface as "no network" and pauses all
// traffic dispatch (route/network.go: dispatchInterfaceUpdateLocked -> NetworkPause(),
// logged as "missing default interface"), even while Android itself has a perfectly
// good network and the VPN tun is up. WireGuard/AmneziaWG peers could still complete
// a handshake (that path doesn't go through this router pause the same way), but no
// data ever got routed afterward, matching the "connects but no data" symptom.
//
// This replaces the stub with a real monitor that Kotlin feeds via the exported
// UpdateDefaultInterface function (see nb4a.go), using the same
// ConnectivityManager.NetworkCallback data the app already collects in
// DefaultNetworkListener/BaseService.preInit(). It resolves the named interface
// through sing's standard control.InterfaceFinder (backed by the OS's own
// net.Interfaces()), so no gomobile listener plumbing is required on the Kotlin side.
type realInterfaceMonitor struct {
	access       sync.Mutex
	finder       control.InterfaceFinder
	current      *control.Interface
	myInterfaces []string
	callbacks    list.List[tun.DefaultInterfaceUpdateCallback]
	initialized  bool
}

var defaultInterfaceMonitor = &realInterfaceMonitor{
	finder: control.NewDefaultInterfaceFinder(),
}

func (s *realInterfaceMonitor) Start() error {
	return nil
}

func (s *realInterfaceMonitor) Close() error {
	return nil
}

func (s *realInterfaceMonitor) DefaultInterface() *control.Interface {
	s.access.Lock()
	defer s.access.Unlock()
	return s.current
}

func (s *realInterfaceMonitor) OverrideAndroidVPN() bool {
	return false
}

func (s *realInterfaceMonitor) AndroidVPNEnabled() bool {
	return false
}

func (s *realInterfaceMonitor) RegisterCallback(callback tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	s.access.Lock()
	defer s.access.Unlock()
	return s.callbacks.PushBack(callback)
}

func (s *realInterfaceMonitor) UnregisterCallback(element *list.Element[tun.DefaultInterfaceUpdateCallback]) {
	s.access.Lock()
	defer s.access.Unlock()
	s.callbacks.Remove(element)
}

func (s *realInterfaceMonitor) RegisterMyInterface(interfaceName string) {
	s.access.Lock()
	defer s.access.Unlock()
	s.myInterfaces = append(s.myInterfaces, interfaceName)
}

func (s *realInterfaceMonitor) MyInterface() string {
	s.access.Lock()
	defer s.access.Unlock()
	if len(s.myInterfaces) == 0 {
		return ""
	}
	return s.myInterfaces[len(s.myInterfaces)-1]
}

func (s *realInterfaceMonitor) MyInterfaces() []string {
	s.access.Lock()
	defer s.access.Unlock()
	return s.myInterfaces
}

// updateDefaultInterfaceInfo is called from the Kotlin side (via the exported
// UpdateDefaultInterface function in nb4a.go) whenever Android's
// ConnectivityManager reports the underlying default network, or that it was lost.
// Pass an empty interfaceName to signal "no network" (mirrors upstream libbox's
// interfaceIndex == -1 case).
func (s *realInterfaceMonitor) updateDefaultInterfaceInfo(interfaceName string) {
	s.access.Lock()
	if interfaceName == "" {
		oldWasNil := s.current == nil
		s.current = nil
		s.initialized = true
		callbacks := s.callbacks.Array()
		s.access.Unlock()
		if oldWasNil {
			return
		}
		for _, callback := range callbacks {
			callback(nil, 0)
		}
		return
	}

	newInterface, err := s.finder.ByName(interfaceName)
	if err != nil {
		s.access.Unlock()
		return
	}
	old := s.current
	unchanged := s.initialized && old != nil && old.Name == newInterface.Name && old.Index == newInterface.Index
	s.current = newInterface
	s.initialized = true
	callbacks := s.callbacks.Array()
	s.access.Unlock()
	if unchanged {
		return
	}
	for _, callback := range callbacks {
		callback(newInterface, 0)
	}
}
