/** Current physical-device views, kept separate from historical development media. */
export const currentProjectMedia:Record<string,{hero:string;images:string[]}>={
 system:{hero:'dashboard-current',images:['launcher-current','controls-current','connection-current','sonar-current-scope']},
 helm:{hero:'helm-current-day',images:['helm-current-night','dashboard-current','dashboard-current-night']},
 audio:{hero:'audio-current-day',images:['audio-current-night','audio-current-presets','audio-current-eq','audio-current-eq-night','audio-current-room','audio-current-room-night','audio-current-speakers','audio-current-positions','audio-current-positions-night','audio-current-position-details','audio-current-routing','audio-current-faders']},
 displays:{hero:'launcher-current',images:['dashboard-current','helm-current-night','controls-current','map-current-overview']},
 maps:{hero:'map-current-overview',images:['map-current-detail','map-current-rotation','map-current-north','map-current-routes','map-current-route-editor','map-current-layers','map-current-objects','map-current-settings','map-current-library','map-current-night']},
 plotters:{hero:'map-current-detail',images:['map-current-overview']},
 sonar:{hero:'sonar-current-scope',images:['sonar-current-range','sonar-current-wide','sonar-current-night','sonar-current-image-controls']},
 electronics:{hero:'helm-current-day',images:['controls-current']},
 build:{hero:'print-preparation',images:[]},
};
export const historicalProjectMedia:Record<string,string[]>={
 system:['helm-day'],helm:['helm-day','helm-night','helm-stale-state'],
 audio:['audio-day','audio-eq-expanded','audio-room-layout','audio-presets'],
 displays:['map-routes-native','map-library-native'],
 maps:['map-cream','map-graphite','map-night-history','routes-editor'],
 plotters:['map-cream'],sonar:['sonar-board','sonar-perfboard-history','sonar-temperature-research','sonar-scope-history'],
 electronics:['yamaha-enclosure','sonar-wiring'],build:['enclosure-exploded','yamaha-enclosure'],
};
