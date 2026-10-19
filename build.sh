#!/usr/bin/env bash
# Usage:
#   ./build.sh                 compile into build/main
#   ./build.sh test            compile and run the JUnit tests
#   ./build.sh run [folder]    compile and start StegoVault (vault folder defaults to ./vault)
set -euo pipefail
cd "$(dirname "$0")"

JUNIT=lib/junit-platform-console-standalone-1.13.0-M3.jar
JAVAC=(javac --release 17 -encoding UTF-8 -Xlint:all,-serial)

compile_main() {
    rm -rf build/main
    "${JAVAC[@]}" -d build/main src/*.java
}

case "${1:-build}" in
    build)
        compile_main
        ;;
    test)
        compile_main
        rm -rf build/test
        "${JAVAC[@]}" -d build/test -cp "build/main:$JUNIT" test/*.java
        java -jar "$JUNIT" execute --disable-banner \
            --class-path "build/main:build/test" --scan-class-path build/test
        ;;
    run)
        compile_main
        shift
        java -cp build/main MainApp "$@"
        ;;
    *)
        echo "Usage: $0 [build|test|run [vault-folder]]" >&2
        exit 2
        ;;
esac
