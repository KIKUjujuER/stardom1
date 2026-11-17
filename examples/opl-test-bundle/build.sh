#!/bin/sh
# ===========================================================================
#  Build the OPLTEST example bundle.
# ===========================================================================
#
#  The program is 16-bit DOS assembly, so it is built with the GNU assembler
#  and linker that come with the repository toolchain:
#
#      as --32 -o opltest.o opltest.asm
#      ld -m elf_i386 --oformat binary -Ttext 0x100 -e _start -o OPLTEST.COM
#
#  The image is linked at 100h because that is where DOS loads a .COM file
#  and enters it, so every label in the source is also its offset inside the
#  loaded segment.
#
#  The result is packed into the bundle zip that WhereQ-DOSBox installs:
#
#      PLAY.BAT       the start-up script the launcher looks for
#      OPLTEST.COM    the program
#      README.TXT     what the bundle is and how to use it
#
#  Usage:  ./build.sh
# ===========================================================================

set -e

here=$(cd "$(dirname "$0")" && pwd)
cd "$here"

toolchain="../../toolchain/env.sh"
if [ ! -f "$toolchain" ]; then
        echo "build.sh: the repository toolchain is missing ($toolchain)" >&2
        exit 1
fi

# the toolchain has to be sourced, not run
. "$toolchain"

mkdir -p build dist

echo "assembling opltest.asm"
as --32 -o build/opltest.o src/opltest.asm

echo "linking opltest.com"
ld -m elf_i386 --oformat binary -Ttext 0x100 -e _start \
        -o build/OPLTEST.COM build/opltest.o

echo "packing the bundle"
rm -f dist/whereq-opl-test-bundle.zip
cp build/OPLTEST.COM bundle/OPLTEST.COM
( cd bundle && zip -q -X ../dist/whereq-opl-test-bundle.zip PLAY.BAT OPLTEST.COM README.TXT )
rm -f bundle/OPLTEST.COM

echo
echo "built:"
ls -l build/OPLTEST.COM dist/whereq-opl-test-bundle.zip
