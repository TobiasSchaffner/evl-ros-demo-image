#
# Ubuntu ships linux-libc-dev as an architecture specific package and isar
# follows that (KERNEL_LIBC_DEV_ARCH_ALL:ubuntu = "0"). For an out-of-tree
# kernel that breaks cross builds: linux-libc-dev is Multi-Arch: same, so
# apt insists on one version across architectures, while the EVL package
# (6.18+r0) only exists for the target and the build architecture keeps
# Ubuntu's (7.0.0-x). The sbuild host chroot then has no solution.
#
# Build it as arch=all instead. That is what isar does on Debian, and its
# cross handling (the -libctarget recipe variant) is written for that case.
#
# SPDX-License-Identifier: MIT
#

KERNEL_LIBC_DEV_ARCH_ALL:ubuntu = "1"
