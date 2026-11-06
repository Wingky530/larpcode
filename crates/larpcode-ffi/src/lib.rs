uniffi::setup_scaffolding!();

#[uniffi::export]
pub fn core_version() -> String {
    larpcode_core::version().to_owned()
}
