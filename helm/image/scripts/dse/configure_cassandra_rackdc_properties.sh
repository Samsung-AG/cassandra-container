#!/usr/bin/env bash

function get_rack {
  cloud_type=$1
  rack_specified=${2:-"rack1"}

  if [[ $cloud_type == "azure" ]]; then
    fault_domain=$(curl --max-time 50000 --retry 12 --retry-delay 50000 http://169.254.169.254/metadata/v1/InstanceInfo -s -S | sed -e 's/.*"FD":"\([^"]*\)".*/\1/')
    if [ ! "$fault_domain" ]; then
      echo Unable to retrieve Instance Fault Domain from instance metadata server 1>&2
	  exit 99
    fi
    rack="FD$fault_domain"
  elif [[ $cloud_type == "aws" ]]; then
    availability_zone=$( curl --max-time 50000 --retry 12 --retry-delay 50000 http://169.254.169.254/latest/meta-data/placement/availability-zone -s -S )
    if [ ! "$availability_zone" ]; then
	  echo Unable to retrieve Instance Availability Zone from instance metadata server 1>&2
	  exit 99
    fi
    rack=$(echo $availability_zone | sed -e 's/-/_/g')
  elif [[ $cloud_type == "gce" ]]; then
    zone=$(curl -s -H "Metadata-Flavor: Google" "http://metadata.google.internal/computeMetadata/v1/instance/zone" | grep -o [[:alnum:]-]*$)
    if [ ! "$zone" ]; then
	  echo Unable to retrieve Instance Zone from instance metadata server 1>&2
	  exit 99
    fi
    rack=$zone
  elif [[ $cloud_type == "gke" ]]; then
    rack=$rack_specified
  else
    echo Cloud type $cloud_type is not supported 1>&2
    exit 99
  fi

  echo $rack
}

cloud_type="$1"
dc="$2"
rack_in="$3"
rack=`get_rack $cloud_type $rack_in`

#
# should only present on a DSE install image (vs DSC21)
file=/etc/dse/cassandra/cassandra-rackdc.properties
if [[ ! -s $file ]]; then
file=/etc/cassandra/cassandra-rackdc.properties
fi

date=$(date +%F)
backup="$file.$date"
cp $file $backup

cat $file \
| sed -e "s:^\(dc\=\).*:dc\=$dc:" \
| sed -e "s:^\(rack\=\).*:rack\=$rack:" \
| sed -e "s:^\(prefer_local\=\).*:rack\=true:" \
> $file.new

mv $file.new $file
