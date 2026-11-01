"""Explicit synthetic environment; fixed scene UTC never changes transport/voyage clocks."""
from .weather import WEATHER_UNITS

def scene_weather(settings, now, session, revision, coordinates):
    lat,lon=coordinates
    scene=settings['sceneEpochMs']
    return {'version':1,'kind':'weather','source':'NO5_PI_SIMULATOR','serverSessionId':session,
        'sourceKind':'SIMULATED','simulated':True,'status':'LIVE',
        'timestampEpochMs':now,'publishedAtEpochMs':now,'observedAtEpochMs':now,
        'provider':{'id':'NO5_SCENE_TEST','label':'SIMULATED · environment test','kind':'SIMULATED'},
        'delivery':{'gatewayId':'NO5_PI_SIMULATOR','transport':'HTTP'},
        'units':dict(WEATHER_UNITS,cloudCoverPercent='percent',visibilityMeters='m'),
        'capabilities':{'airTemperature':False,'wind':True,'humidity':False,'pressure':False,
                        'observedLocation':True,'cloudCover':True,'visibility':True},
        'values':{'airTemperatureC':None,'windSpeedKn':settings['sceneWindKn'],
            'windDirectionDegrees':settings['sceneWindFromDegrees'],'relativeHumidityPercent':None,
            'pressureHpa':None,'latitudeDegrees':lat,'longitudeDegrees':lon,
            'cloudCoverPercent':settings['sceneCloudPercent'],'visibilityMeters':settings['sceneVisibilityMeters']},
        'simulation':{'elapsedSeconds':0.0,'observedAtEpochMs':now,'running':False,
                      'timeScale':0.0,'phase':'SCENE_TEST','scenario':'INDEPENDENT_ENVIRONMENT'},
        'scene':{'version':1,'mode':'SIMULATED','utcEpochMs':scene,'revision':revision,
                 'clockMode':'FIXED','latitudeDegrees':lat,'longitudeDegrees':lon}}
