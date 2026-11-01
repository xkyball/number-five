"""Complete validated settings for the external development simulator only."""
import copy
import math

DEFAULTS = {
    'mode': 'scenario', 'scenario': 'figure8', 'timeScale': 1.0,
    'standingSeconds': 8.0, 'launchSeconds': 22.0, 'cruiseSeconds': 25.0, 'decelerateSeconds': 24.0,
    'routeScale': 1.0, 'launchSpeedKn': 30.0, 'cruiseSpeedKn': 24.0,
    'accelerationMps2': .9, 'decelerationMps2': 1.2,
    'portTrimPercent': 35.0, 'starboardTrimPercent': 35.0,
    'portInitialFuelLiters': 210.0, 'starboardInitialFuelLiters': 210.0,
    'manualSpeedKn': 12.0, 'manualHeadingDegrees': 45.0,
    'clockStartEpochMs': 1790996400000,
    'helmFault': 'none', 'weatherFault': 'none', 'weatherEnabled': True,
    'environmentMode': 'live', 'sceneEpochMs': 1791034200000,
    'sceneWindKn': 12.0, 'sceneWindFromDegrees': 147.0,
    'sceneCloudPercent': 8.0, 'sceneVisibilityMeters': 35000.0,
}
BOUNDS = {
    'sceneEpochMs': (946684800000,4102444800000),
    'sceneWindKn': (0,100), 'sceneWindFromDegrees': (0,359.999),
    'sceneCloudPercent': (0,100), 'sceneVisibilityMeters': (50,100000),
    'timeScale': (.1, 50), 'standingSeconds': (0, 120), 'launchSeconds': (1, 180),
    'cruiseSeconds': (1, 600), 'decelerateSeconds': (1, 120), 'routeScale': (.25, 4),
    'launchSpeedKn': (1, 45), 'cruiseSpeedKn': (1, 35),
    'accelerationMps2': (.1, 2), 'decelerationMps2': (.1, 3),
    'portTrimPercent': (0,100), 'starboardTrimPercent': (0,100),
    'portInitialFuelLiters': (0,250), 'starboardInitialFuelLiters': (0,250),
    'manualSpeedKn': (0,45), 'manualHeadingDegrees': (0,359.999),
    'clockStartEpochMs': (946684800000,4102444800000),
}
CHOICES = {'environmentMode': ('live','simulated'),'mode': ('scenario','manual'), 'scenario': ('figure8','idle'),
           'helmFault': ('none','stale','failed'), 'weatherFault': ('none','stale','failed')}
COLD_SETTINGS = {'mode','scenario','standingSeconds','launchSeconds','cruiseSeconds','decelerateSeconds',
                 'routeScale','launchSpeedKn','cruiseSpeedKn','accelerationMps2','decelerationMps2',
                 'portInitialFuelLiters','starboardInitialFuelLiters','clockStartEpochMs'}


def validate_settings(value):
    if not isinstance(value, dict) or set(value) != set(DEFAULTS):
        raise ValueError('settings must contain every supported key exactly once')
    result = copy.deepcopy(value)
    for key, (low, high) in BOUNDS.items():
        item = result[key]
        if isinstance(item,bool) or not isinstance(item,(int,float)) or not math.isfinite(item) or not low <= item <= high:
            raise ValueError(f'{key} must be a finite number from {low} to {high}')
    if type(result['clockStartEpochMs']) is not int or type(result['sceneEpochMs']) is not int:
        raise ValueError('clockStartEpochMs must be an integer UTC epoch in milliseconds')
    for key, options in CHOICES.items():
        if result[key] not in options:
            raise ValueError(f'{key} must be one of: {", ".join(options)}')
    if not isinstance(result['weatherEnabled'],bool):
        raise ValueError('weatherEnabled must be boolean')
    return result
