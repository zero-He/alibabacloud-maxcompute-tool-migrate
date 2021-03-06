#!/bin/bash
set -e

echo "Release starts"

if [ "" = "${TRAVIS_TAG}" ];
then
  echo "Not a release"
  ./build.sh
else
  echo "Build release branch origin/release/hive-1.x"
  git checkout --quiet release/hive-1.x
  ./build.sh
  mv mma.tar.gz mma-hive-1.x-release.tar.gz
  echo "Done"

  echo "Build release branch origin/release/hive-2.x"
  git checkout --quiet release/hive-2.x
  ./build.sh
  mv mma.tar.gz mma-hive-2.x-release.tar.gz
  echo "Done"

  echo "Build release branch origin/release/hive-3.x"
  git checkout --quiet release/hive-3.x
  ./build.sh
  mv mma.tar.gz mma-hive-3.x-release.tar.gz
  echo "Done"
fi