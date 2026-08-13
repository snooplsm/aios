# AIOS is a derived Pixel product. Reuse the generated Pixel product build ID so
# Android validates AIOS against the same vendor, firmware and driver release.
aios_tegu_env="$(gettop)/vendor/google_devices/tegu/cmds-for-envsetup.sh"
if [[ -f "$aios_tegu_env" ]]; then
  # shellcheck disable=SC1090
  source "$aios_tegu_env"
  export BUILD_ID_aios_tegu="$BUILD_ID_tegu"
fi
unset aios_tegu_env
