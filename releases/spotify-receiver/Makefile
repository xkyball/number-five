.PHONY: check test pi-release

check:
	cargo check

test:
	cargo test

# Run only on lysmarine (Debian aarch64) after confirming libasound2-dev and
# the existing Camilla ALSA route. This adds the actual ALSA sink feature.
pi-release:
	cargo build --release --features alsa
