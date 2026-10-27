#!/usr/bin/env bash
# Baut HomeX + Plugins und startet den Server im Ordner ./server
set -e
mvn -q package
mkdir -p server/plugins
cp homex-core/target/HomeX.jar server/
cp homex-plugins/*/target/*.jar server/plugins/
cd server
java -Dstdout.encoding=UTF-8 -jar HomeX.jar
