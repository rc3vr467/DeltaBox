// MOCK of the OpenVPN 3 client API, for syntax/override checking only.
// Signatures are copied from the vendored openvpn3 sources (see comments).
#pragma once
#include <map>
#include <string>
#include <vector>
#include <cstdint>

namespace openvpn_io { namespace detail { using socket_type = int; } }

struct DnsAddress { std::string address; unsigned int port = 0; };
struct DnsDomain { std::string domain; };
struct DnsServer { std::vector<DnsAddress> addresses; std::vector<DnsDomain> domains; };
struct DnsOptions { std::map<int, DnsServer> servers; std::vector<DnsDomain> search_domains; };

// openvpn/tun/builder/base.hpp
class TunBuilderBase {
public:
    virtual bool tun_builder_new() { return false; }
    virtual bool tun_builder_set_layer(int layer) { (void)layer; return false; }
    virtual bool tun_builder_set_remote_address(const std::string &address, bool ipv6) { (void)address; (void)ipv6; return false; }
    virtual bool tun_builder_add_address(const std::string &address, int prefix_length, const std::string &gateway, bool ipv6, bool net30) { (void)address;(void)prefix_length;(void)gateway;(void)ipv6;(void)net30; return false; }
    virtual bool tun_builder_set_route_metric_default(int metric) { (void)metric; return false; }
    virtual bool tun_builder_reroute_gw(bool ipv4, bool ipv6, unsigned int flags) { (void)ipv4;(void)ipv6;(void)flags; return false; }
    virtual bool tun_builder_add_route(const std::string &address, int prefix_length, int metric, bool ipv6) { (void)address;(void)prefix_length;(void)metric;(void)ipv6; return false; }
    virtual bool tun_builder_exclude_route(const std::string &address, int prefix_length, int metric, bool ipv6) { (void)address;(void)prefix_length;(void)metric;(void)ipv6; return false; }
    virtual bool tun_builder_set_dns_options(const DnsOptions &dns) { (void)dns; return false; }
    virtual bool tun_builder_set_mtu(int mtu) { (void)mtu; return false; }
    virtual bool tun_builder_set_session_name(const std::string &name) { (void)name; return false; }
    virtual bool tun_builder_add_proxy_bypass(const std::string &bypass_host) { (void)bypass_host; return false; }
    virtual bool tun_builder_set_proxy_auto_config_url(const std::string &url) { (void)url; return false; }
    virtual bool tun_builder_set_proxy_http(const std::string &host, int port) { (void)host;(void)port; return false; }
    virtual bool tun_builder_set_proxy_https(const std::string &host, int port) { (void)host;(void)port; return false; }
    virtual bool tun_builder_add_wins_server(const std::string &address) { (void)address; return false; }
    virtual int tun_builder_establish() { return -1; }
    virtual void tun_builder_teardown(bool disconnect) { (void)disconnect; }
    virtual ~TunBuilderBase() = default;
};

struct LogInfo { std::string text; };
struct Config { std::string content; std::string protoOverride; };
struct EvalConfig { bool error = false; std::string message; };
struct ProvideCreds { std::string username; std::string password; };
struct Status { bool error = false; std::string status; std::string message; };
struct Event { bool error = false; bool fatal = false; std::string name; std::string info; };
struct AppCustomControlMessageEvent {};
struct ExternalPKICertRequest { bool error = false; std::string errorText; };
struct ExternalPKISignRequest { bool error = false; std::string errorText; };

class LogReceiver {
public:
    virtual void log(const LogInfo &) = 0;
    virtual ~LogReceiver() = default;
};

// openvpn/client/... (OpenVPNClient in client/ovpncli.hpp)
class OpenVPNClient : public TunBuilderBase, public LogReceiver {
public:
    virtual ~OpenVPNClient() = default;
    virtual bool socket_protect(openvpn_io::detail::socket_type socket, std::string remote, bool ipv6);
    EvalConfig eval_config(const Config &);
    Status provide_creds(const ProvideCreds &);
    Status connect();
    void stop();
    virtual bool pause_on_connection_timeout() = 0;
    virtual void event(const Event &) = 0;
    virtual void acc_event(const AppCustomControlMessageEvent &) = 0;
    virtual void external_pki_cert_request(ExternalPKICertRequest &) = 0;
    virtual void external_pki_sign_request(ExternalPKISignRequest &) = 0;
};
