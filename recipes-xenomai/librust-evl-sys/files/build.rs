// evl-sys build script, patched for packaged builds.
//
// Differs from upstream only in where generated files land: everything
// goes to OUT_DIR, because the crate source lives in the read-only
// system Cargo registry when downstream packages build it.

use std::env;
use std::path::PathBuf;

fn main() {
    println!("cargo:rustc-link-lib=evl");
    println!("cargo:rerun-if-changed=wrapper.h");

    // Yocto/SDK integration: point clang at the sysroot if one is set.
    if env::var("BINDGEN_EXTRA_CLANG_ARGS").is_err() {
        if let Ok(sysroot) = env::var("PKG_CONFIG_SYSROOT_DIR") {
            env::set_var("BINDGEN_EXTRA_CLANG_ARGS", format!("--sysroot={sysroot}"));
        }
    }

    let out_dir = PathBuf::from(env::var("OUT_DIR").unwrap());
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let static_fns_path = out_dir.join("static_fns_wrappers");

    let bindings = bindgen::Builder::default()
        .size_t_is_usize(true)
        .allowlist_function("evl_.*")
        .allowlist_type("evl_.*")
        .allowlist_var("evl_.*")
        .allowlist_function("oob_.*")
        .blocklist_function("evl_sigdebug_handler")
        .blocklist_type("siginfo_.*")
        .clang_args([&format!("-I{}", manifest_dir.join("vendor").display()), "-D_GNU_SOURCE"])
        .header(manifest_dir.join("wrapper.h").to_str().unwrap())
        .parse_callbacks(Box::new(bindgen::CargoCallbacks::new()))
        .wrap_static_fns(true)
        .wrap_static_fns_path(static_fns_path.to_str().unwrap())
        .generate()
        .expect("Unable to generate bindings");

    // bindgen only emits this when the headers actually contain allowlisted
    // static inline functions — as of API 45 they do not.
    let static_fns_c = out_dir.join("static_fns_wrappers.c");
    if static_fns_c.exists() {
        cc::Build::new()
            .file(&static_fns_c)
            .include(manifest_dir.join("vendor"))
            .compile("static_fns_wrappers");
    }

    bindings
        .write_to_file(out_dir.join("bindings.rs"))
        .expect("Couldn't write bindings!");
}
