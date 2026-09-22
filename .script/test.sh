#!/bin/bash
set -e

cd "$(dirname "$0")/.."
source .script/jdk.sh

mvn test
