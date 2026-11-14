#!/bin/bash

set -e

DIR=app/src/main/assets/sing-box
rm -rf $DIR
mkdir -p $DIR
cd $DIR

get_latest_release() {
  curl --silent "https://api.github.com/repos/$1/releases/latest" | # Get latest release from GitHub api
    grep '"tag_name":' |                                            # Get tag line
    sed -E 's/.*"([^"]+)".*/\1/'                                    # Pluck JSON value
}

####
# SagerNet/sing-geoip is gone; soffchen/sing-geoip is the maintained fork.
VERSION_GEOIP=`get_latest_release "soffchen/sing-geoip"`
if [ -z "$VERSION_GEOIP" ]; then
  echo "ERROR: could not resolve latest release for soffchen/sing-geoip" >&2
  exit 1
fi
echo VERSION_GEOIP=$VERSION_GEOIP
echo -n $VERSION_GEOIP > geoip.version.txt
curl -fLSsO https://github.com/soffchen/sing-geoip/releases/download/$VERSION_GEOIP/geoip.db
xz -9 geoip.db

####
# SagerNet/sing-geosite is gone; soffchen/sing-geosite is the maintained fork.
VERSION_GEOSITE=`get_latest_release "soffchen/sing-geosite"`
if [ -z "$VERSION_GEOSITE" ]; then
  echo "ERROR: could not resolve latest release for soffchen/sing-geosite" >&2
  exit 1
fi
echo VERSION_GEOSITE=$VERSION_GEOSITE
echo -n $VERSION_GEOSITE > geosite.version.txt
curl -fLSsO https://github.com/soffchen/sing-geosite/releases/download/$VERSION_GEOSITE/geosite.db
xz -9 geosite.db
