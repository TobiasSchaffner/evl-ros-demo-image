#
# librust-r2r: ROS 2 Rust client library (r2r) crate sources
#
# r2r is not packaged by Ubuntu, so its crate sources are installed into
# /usr/share/cargo/registry/ for downstream dh-cargo builds. Everything r2r
# depends on comes from Ubuntu's librust-*-dev packages; only force-send-sync
# has to be shipped here as well.
#
# SPDX-License-Identifier: MIT
#

inherit dpkg

DESCRIPTION = "ROS 2 Rust client library crate sources"

R2R_VER = "${PV}"
FSS_VER = "1.2.0"

CRATES_BASE = "https://static.crates.io/crates"

SRC_URI = " \
    ${CRATES_BASE}/r2r/r2r-${R2R_VER}.crate;sha256sum=1fe8623ef13a4f3c25f4ef407c6032eee9e1c95d0912d8ed758f616cbb95a6cd \
    ${CRATES_BASE}/r2r_actions/r2r_actions-${R2R_VER}.crate;sha256sum=441f4aca2b1dfe553ab40f03bd869445d02dccd7ddff4c5f32e3a681fcd47c57 \
    ${CRATES_BASE}/r2r_common/r2r_common-${R2R_VER}.crate;sha256sum=786a8b50036ed5d38a1dfbee73c711e270acd9a518b73351c66d541c1fa05422 \
    ${CRATES_BASE}/r2r_macros/r2r_macros-${R2R_VER}.crate;sha256sum=743bfcbea83d856d97d222b3c3545d4848ba81572f1d52dc5b6bff4899b8583d \
    ${CRATES_BASE}/r2r_msg_gen/r2r_msg_gen-${R2R_VER}.crate;sha256sum=db410864937e12357cd65f3e82c6fb7c8372d0493f154886952522bad87817c9 \
    ${CRATES_BASE}/r2r_rcl/r2r_rcl-${R2R_VER}.crate;sha256sum=7c0420d9ea790fe2b4f66f7a88c8a7901491075d2fed4037010b03173392a22a \
    ${CRATES_BASE}/force-send-sync/force-send-sync-${FSS_VER}.crate;sha256sum=eb5682bd193aa441fe571a005561e0c0ada74f89ec2dc390483a6ff17c0665b9 \
    file://debian \
"

S = "${WORKDIR}/r2r-build"

DEBIAN_BUILD_DEPENDS = "debhelper-compat (= ${DEBIAN_COMPAT})"

TEMPLATE_FILES = "debian/control.tmpl"
TEMPLATE_VARS += "DEBIAN_BUILD_DEPENDS"

CRATES = " \
    r2r-${R2R_VER} \
    r2r_actions-${R2R_VER} \
    r2r_common-${R2R_VER} \
    r2r_macros-${R2R_VER} \
    r2r_msg_gen-${R2R_VER} \
    r2r_rcl-${R2R_VER} \
    force-send-sync-${FSS_VER} \
"

do_prepare_build() {
    rm -rf ${S}
    mkdir -p ${S}

    for crate in ${CRATES}; do
        tar -C ${S} -xzf ${WORKDIR}/${crate}.crate
    done

    # Relax two dependency bounds onto the versions Ubuntu ships. Both are
    # API-compatible with what r2r uses (RawOsString for os_str_bytes;
    # chain/iproduct/Either/partition_map for itertools).
    sed -i 's/^version = "6.5.1"/version = "7"/' \
        ${S}/r2r_common-${R2R_VER}/Cargo.toml
    sed -i 's/^version = "0.10.5"/version = "0.14"/' \
        ${S}/r2r_msg_gen-${R2R_VER}/Cargo.toml

    rm -rf ${S}/debian
    cp -r ${WORKDIR}/debian ${S}/
    chmod 0755 ${S}/debian/rules
    sed -i "s|@CRATES@|${CRATES}|" ${S}/debian/rules

    deb_add_changelog
}
