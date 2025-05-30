#! /usr/bin/env bash
#
# Copyright (c) 2013-%%copyright.year%% Commonwealth Computer Research, Inc.
# All rights reserved. This program and the accompanying materials
# are made available under the terms of the Apache License, Version 2.0 which
# accompanies this distribution and is available at
# http://www.opensource.org/licenses/apache2.0.php.
#

# This file lists the dependencies required for running the geomesa-accumulo command-line tools.
# Usually these dependencies will be provided by the environment (e.g. ACCUMULO_HOME).
# Update the versions as required to match the target environment.

accumulo_install_version="%%accumulo.version.recommended%%"
hadoop_install_version="%%hadoop.version.recommended%%"
zookeeper_install_version="%%zookeeper.version.recommended%%"
kafka_install_version="%%kafka.version%%"
# required for hadoop - make sure it corresponds to the hadoop installed version
guava_install_version="%%accumulo.guava.version%%"

# gets the dependencies for this module
# args:
#   $1 - current classpath
function dependencies() {
  local classpath="$1"

  local accumulo_version="$accumulo_install_version"
  local hadoop_version="$hadoop_install_version"
  local zk_version="$zookeeper_install_version"
  local kafka_version="$kafka_install_version"

  if [[ -n "$classpath" ]]; then
    accumulo_version="$(get_classpath_version accumulo-core "$classpath" "$accumulo_version")"
    hadoop_version="$(get_classpath_version hadoop-common "$classpath" "$hadoop_version")"
    hadoop_version="$(get_classpath_version hadoop-client-api "$classpath" "$hadoop_version")"
    zk_version="$(get_classpath_version zookeeper "$classpath" "$zk_version")"
    kafka_version="$(get_classpath_version kafka-clients "$classpath" $kafka_version)"
  fi

  if [[ "$hadoop_version" == "3.2.3" ]]; then
    echo >&2 "WARNING Updating Hadoop version from 3.2.3 to 3.2.4 due to invalid client-api Maven artifacts"
    hadoop_version="3.2.4"
  fi

  declare -a gavs=(
    "org.apache.accumulo:accumulo-core:${accumulo_version}:jar"
    "org.apache.accumulo:accumulo-server-base:${accumulo_version}:jar"
    "org.apache.accumulo:accumulo-start:${accumulo_version}:jar"
    "org.apache.accumulo:accumulo-hadoop-mapreduce:${accumulo_version}:jar"
    "org.apache.zookeeper:zookeeper:${zk_version}:jar"
    "org.apache.hadoop:hadoop-client-api:${hadoop_version}:jar"
    "org.apache.hadoop:hadoop-client-runtime:${hadoop_version}:jar"
    "org.apache.commons:commons-configuration2:2.10.1:jar"
    "commons-logging:commons-logging:1.3.3:jar"
    "org.apache.commons:commons-text:%%commons.text.version%%:jar"
    "org.apache.commons:commons-vfs2:2.9.0:jar"
    "org.apache.kafka:kafka-clients:${kafka_version}:jar"
    "com.google.guava:guava:${guava_install_version}:jar"
    "net.sf.jopt-simple:jopt-simple:5.0.4:jar"
    "io.netty:netty-codec:%%netty.version%%:jar"
    "io.netty:netty-handler:%%netty.version%%:jar"
    "io.netty:netty-resolver:%%netty.version%%:jar"
    "io.netty:netty-transport:%%netty.version%%:jar"
    "io.netty:netty-transport-classes-epoll:%%netty.version%%:jar"
    "io.netty:netty-transport-native-epoll:%%netty.version%%:jar:linux-x86_64"
    "io.netty:netty-transport-native-unix-common:%%netty.version%%:jar"
  )

  # add accumulo 2.1 jars if needed
  if version_ge "${accumulo_version}" 2.1.0; then
    local micrometer_version
    local opentelemetry_version

    if version_ge "${accumulo_version}" 2.1.3; then
      micrometer_version="1.12.2"
      opentelemetry_version="1.34.1"
    else
      # these versions seem compatible even though they're not the exact versions shipped
      micrometer_version="1.11.1"
      opentelemetry_version="1.27.0"
      gavs+=(
        "io.opentelemetry:opentelemetry-semconv:${opentelemetry_version}-alpha:jar"
      )
    fi

    gavs+=(
      "org.apache.thrift:libthrift:%%thrift.version%%:jar"
      "io.micrometer:micrometer-core:${micrometer_version}:jar"
      "io.micrometer:micrometer-commons:${micrometer_version}:jar"
      "io.opentelemetry:opentelemetry-api:${opentelemetry_version}:jar"
      "io.opentelemetry:opentelemetry-context:${opentelemetry_version}:jar"
    )
  else
    gavs+=(
      "org.apache.thrift:libthrift:0.12.0:jar"
      "org.apache.htrace:htrace-core:3.1.0-incubating:jar"
    )
  fi

  if ! version_ge "${hadoop_version}" 3.3.0; then
    gavs+=(
      "org.apache.htrace:htrace-core4:4.1.0-incubating:jar"
    )
  fi

  # compare the version of zookeeper to determine if we need zookeeper-jute (version >= 3.5.5)
  JUTE_FROM_VERSION="3.5.5"
  if version_ge "${zk_version}" $JUTE_FROM_VERSION; then
    gavs+=(
      "org.apache.zookeeper:zookeeper-jute:${zk_version}:jar"
    )
  fi

  echo "${gavs[@]}" | tr ' ' '\n' | sort | tr '\n' ' '
}

# gets any dependencies that should be removed from the classpath for this module
# args:
#   $1 - current classpath
function exclude_dependencies() {
  echo "commons-text-1.4.jar"
}
