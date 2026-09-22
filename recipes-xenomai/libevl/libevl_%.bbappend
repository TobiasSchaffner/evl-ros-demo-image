# Ubuntu Noble's dpkg-buildflags adds -D_FORTIFY_SOURCE=3, which conflicts
# with libevl's meson.build that sets -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=2.
# With -Werror, the redefinition is fatal. Strip it from CPPFLAGS entirely
# so meson's own flags are authoritative.
do_prepare_build:append() {
    sed -i '/^#!.*make/a export DEB_CPPFLAGS_MAINT_STRIP = -D_FORTIFY_SOURCE=3' \
        ${S}/debian/rules
}
