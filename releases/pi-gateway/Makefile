PYTHON ?= python3
UV ?= uv
UV_CACHE_DIR ?= .uv-cache

.PHONY: test package

test:
	PYTHONPATH=src $(PYTHON) -m unittest discover -s tests -v

package:
	UV_CACHE_DIR=$(UV_CACHE_DIR) $(UV) build --wheel --out-dir dist
