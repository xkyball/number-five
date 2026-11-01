"""The qualification observes guest sonar pixels, and rejects blank pictures."""
import importlib.util
import io
from pathlib import Path

import numpy as np
from PIL import Image
import pytest

spec = importlib.util.spec_from_file_location('permanent_chain', Path(__file__).parents[1]/'tools/verify_permanent_chain.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def png(pixels):
    output = io.BytesIO()
    Image.fromarray(pixels).save(output, format='PNG')
    return output.getvalue()


def test_black_capture_is_not_live_and_footer_motion_is_excluded():
    image = np.zeros((1200, 2000, 3), dtype=np.uint8)
    before, view = module.framebuffer(png(image))
    assert view['plot_flat']
    image[1000:, :, :] = 255
    footer, view = module.framebuffer(png(image))
    assert view['plot_flat'] and np.array_equal(before, footer)
    image[400:410, 600:610] = 200
    moving, view = module.framebuffer(png(image))
    assert not view['plot_flat'] and np.any(moving != before)
    assert view['capture_method'] == 'android_guest_exec_out_screencap'


def test_wrong_geometry_and_invalid_capture_fail_closed():
    with pytest.raises(RuntimeError, match='geometry'):
        module.framebuffer(png(np.zeros((1200, 1000, 3), dtype=np.uint8)))
    with pytest.raises(Exception):
        module.framebuffer(b'not a PNG')
