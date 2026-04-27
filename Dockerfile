# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

FROM maven:3.9-eclipse-temurin-8 AS build

WORKDIR /src
COPY . .
RUN mvn -pl kerby-dist/kdc-dist -am -Pdist -DskipTests package
RUN mkdir -p /opt/kerby \
    && tar -xzf kerby-dist/kdc-dist/target/kdc-dist-*.tar.gz -C /opt/kerby --strip-components=1

FROM eclipse-temurin:8-jre

WORKDIR /opt/kerby
COPY --from=build /opt/kerby /opt/kerby
COPY docker/kdc-entrypoint.sh /opt/kerby/bin/kdc-entrypoint.sh
RUN chmod +x /opt/kerby/bin/*.sh \
    && mkdir -p /var/lib/kerby /var/run/kerby

EXPOSE 88/tcp 88/udp
ENTRYPOINT ["/opt/kerby/bin/kdc-entrypoint.sh"]

