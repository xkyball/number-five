"""Optional Pillow adapter for exact static PNG-to-straight-RGBA8 snapshots."""
from io import BytesIO
import warnings

from .bitmap_pivots import RGBAImage


def decode_png_rgba(raw):
    """Decode supplied bytes only; never search for files or transform colours.

    Animated and 16-bit PNGs are outside this byte-equality contract. Pillow's
    normal decompression-bomb checks remain active and warnings are failures.
    No resizing, compositing, premultiplication or ICC conversion is performed.
    """
    if (not isinstance(raw, bytes) or len(raw) < 33 or
            raw[:16] != b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR'):
        raise ValueError('STATIC_RGBA8_PNG_REQUIRED')
    if raw[24] not in (1, 2, 4, 8):
        raise ValueError('PNG_BIT_DEPTH_OUTSIDE_RGBA8_CONTRACT')
    try:
        from PIL import Image
    except ImportError as error:
        raise ValueError('BITMAP_PNG_REQUIRES_PILLOW_EXTRA') from error
    try:
        with warnings.catch_warnings():
            warnings.simplefilter('error', Image.DecompressionBombWarning)
            with Image.open(BytesIO(raw), formats=['PNG']) as image:
                if image.format != 'PNG' or getattr(image, 'n_frames', 1) != 1:
                    raise ValueError('STATIC_SINGLE_FRAME_PNG_REQUIRED')
                image.verify()
            with Image.open(BytesIO(raw), formats=['PNG']) as image:
                rgba = image.convert('RGBA')
                try:
                    return RGBAImage(rgba.width, rgba.height, rgba.tobytes())
                finally:
                    rgba.close()
    except (Image.DecompressionBombWarning, Image.DecompressionBombError) as error:
        raise ValueError('PNG_EXCEEDS_PILLOW_DECOMPRESSION_BUDGET') from error
