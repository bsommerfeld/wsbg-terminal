#version 410 core
out vec4 fragColor;

uniform sampler2D uNoise;
uniform vec2  uRes;        // Größe in Gerätepixeln
uniform float uTime;
uniform float uHover;      // 0 = Normalzustand, 1 = voll gehovert
uniform vec3  uPal[4];     // Farbverlauf des Sturms: tief -> dunkel -> mittel -> hell
uniform vec3  uCloud;      // Wolkenfarbe
uniform vec3  uLine;       // Farbe des Rings im Normalzustand

const float PI = 3.14159265;
const float TEXN = 256.0;
const float OUTER_R = 400.0;
const float SVG_RING = 80.0;
const float SWIRL = 0.12;
const float FLOW_SPEED = 0.035;
const float CLOUD_SPEED = 0.09;
const float IOR = 1.5;                                  // Brechungsindex Glas
const float CORE_R = 0.82;                              // Sturmkern; darum herum klares Glas

vec4 noise(vec2 uv) { return texture(uNoise, uv + 0.5 / TEXN); }

vec3 palette(float t) {
    if (t < 0.45) return mix(uPal[0], uPal[1], t / 0.45);
    if (t < 0.70) return mix(uPal[1], uPal[2], (t - 0.45) / 0.25);
    return mix(uPal[2], uPal[3], (t - 0.70) / 0.30);
}

// Sichtbarkeit beim Erscheinen (Wolkenfront von außen + Nebel von unten, treffen sich in der Mitte);
// q = Position in der Kugel (-1..1, y nach unten).
float reveal(vec2 q, float p, float t) {
    float r = length(q);
    float a = atan(q.y, q.x);
    float nf = clamp((noise(q * 0.3 + vec2(0.17, 0.41)).r - 0.25) / 0.5, 0.0, 1.0);
    float wave = 0.035 * sin(q.x * 7.0 + t * 5.0) + 0.02 * sin(q.x * 13.0 - t * 7.0);
    float pp = p;

    // Nebel steigt von unten, mit wolkig ausgefranster Oberfläche
    float mist = clamp((q.y - (1.31 - pp * 2.18 + (nf - 0.5) * 0.5 + wave)) / 0.12, 0.0, 1.0);

    // Wolkenfront von außen
    float edge = 1.28 - pp * 1.42 + (nf - 0.5) * 0.45
               + 0.05 * sin(3.0 * a + r * 6.0 - t * 3.0) * smoothstep(0.0, 0.45, r);
    float front = clamp((r - edge) / 0.12, 0.0, 1.0);
    // letzte Reste zum Schluss weich schließen
    return max(max(mist, front), smoothstep(0.9, 1.0, p));
}

// ===== Physikalisch basierte Glasmurmel (Raytracing + Volumen-Raymarching) =====
// Welt: x rechts, y oben, z zum Betrachter. Kugelradius 1, orthografische Kamera blickt entlang -z.
const float STORM_R = 0.86;                                // Sturmvolumen; außen klares Glas
const vec3  LIGHT_DIR = normalize(vec3(-0.5, 0.65, 0.55)); // Hauptlicht (Softbox) oben links vorne
const float DENSITY = 15.0;                                // Extinktion der Wolken pro Kugelradius
const vec3  GLASS_ABS = vec3(0.06, 0.02, 0.04);            // Beer-Lambert im Glas (leichter Grünstich)
const int   STEPS = 40;                                    // Proben entlang des Sehstrahls
const int   LIGHT_STEPS = 4;                               // Proben Richtung Licht (Selbstverschattung)

vec3 toLinear(vec3 c) { return pow(c, vec3(2.2)); }
vec3 toSRGB(vec3 c) { return pow(max(c, 0.0), vec3(1.0 / 2.2)); }
vec3 softClip(vec3 c) {                                    // Lichter weich auslaufen lassen statt hart abschneiden
    return mix(c, 0.8 + 0.2 * (1.0 - exp(-(c - 0.8) / 0.2)), step(0.8, c));
}

// exakte Fresnel-Gleichung für unpolarisiertes Licht (eta = n2 / n1)
float fresnel(float cosi, float eta) {
    float sint2 = (1.0 - cosi * cosi) / (eta * eta);
    if (sint2 > 1.0) return 1.0;
    float cost = sqrt(1.0 - sint2);
    float rs = (cosi - eta * cost) / (cosi + eta * cost);
    float rp = (eta * cosi - cost) / (eta * cosi + cost);
    return 0.5 * (rs * rs + rp * rp);
}

// Studio-Umgebung: ein Raum im hellen Ton der Palette, etwas dunklerer Boden, dunkle Seite hinter
// der Kamera. Bewusst ohne gespiegelte Lichtquellen (minimalistisch) – sie formt nur den Glasrand.
// Getönt statt weiß, damit das klare Glas beim Aufgehen die Farbe des Orbs trägt.
vec3 environment(vec3 d) {
    float room = mix(0.72, 1.0, smoothstep(-0.25, 0.15, d.y));
    room *= mix(1.0, 0.28, smoothstep(0.25, 0.95, d.z));
    return toLinear(uPal[3]) * room;
}

// Sturm an Punkt p im Kugelinneren: gleiche Felder und gleiche Animation wie auf der Oberfläche,
// je Tiefe leicht versetzt, damit echte Schichten entstehen
void stormAt(vec3 p, float t, bool cheap, out vec3 albedo, out float dens) {
    float r = length(p);
    // Rückseite gespiegelt (keine Naht hinten), an der Drehachse weich auf einen stabilen Wert
    float axis = smoothstep(0.0, 0.3, length(p.xz) / max(r, 1e-4));
    float lon = atan(p.x, abs(p.z)) / PI * 0.5 * axis + (STORM_R - r) * 0.15;
    float lat = asin(clamp(-p.y / max(r, 1e-4), -1.0, 1.0)) / PI * 0.5;
    float wu = 0.0, wv = 0.0;
    if (!cheap) {
        wu = noise(vec2(lon * 0.8 + t * 0.02, lat * 0.8 - t * 0.013)).g - 0.5;
        wv = noise(vec2(lon * 0.8 - t * 0.017, lat * 0.8 + t * 0.021)).b - 0.5;
    }
    float base = noise(vec2(lon + wu * SWIRL * 2.0 + t * FLOW_SPEED, (lat + wv * SWIRL) * 1.6)).r;
    float cl = noise(vec2(lon * 1.2 + wu * SWIRL * 3.0 + t * CLOUD_SPEED, lat * 1.8 + wv * SWIRL * 2.0)).a;
    // an der Drehachse (Pole) ist die Textur zusammengequetscht -> dort Wolken ausblenden, Grundton beruhigen
    float cloud = smoothstep(0.48, 0.78, cl) * axis;
    base = mix(0.55, base, 0.4 + 0.6 * axis);
    albedo = toLinear(mix(palette(base), uCloud, cloud * 0.9));
    // mehrere Schichten mischen sich im Volumen -> Sättigung ausgleichen, damit die Farben so kräftig
    // wirken wie auf der flachen Murmel
    albedo = max(mix(vec3(dot(albedo, vec3(0.2126, 0.7152, 0.0722))), albedo, 1.35), 0.0);
    float shell = smoothstep(0.18, 0.4, r) * (1.0 - smoothstep(STORM_R - 0.1, STORM_R, r));
    dens = shell * (0.45 + 1.1 * smoothstep(0.3, 0.75, base) + 0.45 * cloud);
}

// Anteil Licht, der vom Punkt p aus die Lichtquelle erreicht (Beer-Lambert entlang des Lichtstrahls)
float lightTransmittance(vec3 p, float t, float revealMask) {
    float b = dot(p, LIGHT_DIR);
    float c = dot(p, p) - STORM_R * STORM_R;
    float dist = -b + sqrt(max(b * b - c, 0.0));
    float ds = dist / float(LIGHT_STEPS);
    float optical = 0.0;
    for (int i = 0; i < LIGHT_STEPS; i++) {
        vec3 albedo; float dens;
        stormAt(p + LIGHT_DIR * (float(i) + 0.5) * ds, t, true, albedo, dens);
        optical += dens * ds;
    }
    return exp(-optical * DENSITY * revealMask);
}

vec4 litMarble(vec2 q, float h, float t, float edgeAA, float radiusPx) {
    float g = smoothstep(0.0, 0.3, h) * edgeAA;             // Glas erscheint zuerst
    float z = sqrt(max(1.0 - dot(q, q), 0.0));
    vec3 P = vec3(q.x, -q.y, z);                            // Eintrittspunkt = Normale
    vec3 V = vec3(0.0, 0.0, -1.0);

    // 1) Oberfläche: Fresnel teilt in Spiegelung und Brechung
    float F = fresnel(z, IOR);
    vec3 reflected = environment(reflect(V, P));
    vec3 T = refract(V, P, 1.0 / IOR);
    float sExit = -2.0 * dot(P, T);                         // Weg durch die Glaskugel
    vec3 P2 = P + T * sExit;

    // 2) Inneres: Raymarching durch das Sturmvolumen (Absorption + Einfachstreuung mit Selbstverschattung)
    vec3 scattered = vec3(0.0);
    float trans = 1.0;
    float b = dot(P, T);
    float disc = b * b - (dot(P, P) - STORM_R * STORM_R);
    if (disc > 0.0) {
        float s0 = -b - sqrt(disc), s1 = -b + sqrt(disc);
        float ds = (s1 - s0) / float(STEPS);
        // pro Pixel leicht versetzte Proben: verhindert sichtbare Scheiben/Stufen im Volumen
        float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
        for (int i = 0; i < STEPS; i++) {
            vec3 p = P + T * (s0 + (float(i) + jitter) * ds);
            vec3 albedo; float dens;
            stormAt(p, t, false, albedo, dens);
            float mask = reveal(vec2(p.x, -p.y) / STORM_R, h, t);
            dens *= mask;
            if (dens < 1e-3) continue;
            float lightT = lightTransmittance(p, t, mask);
            float alpha = 1.0 - exp(-dens * DENSITY * ds);
            // Farbe bleibt die Sturmfarbe; Licht/Schatten modulieren nur sanft (Tiefe ohne Farbverlust)
            float facing = sqrt(max(p.z, 0.0) / max(length(p), 1e-4));   // zum Rand hin dunkler wie flach
            vec3 inscatter = albedo * mix(0.75, 1.0, lightT) * (0.68 + 0.32 * facing);
            scattered += trans * alpha * inscatter;
            trans *= 1.0 - alpha;
            if (trans < 0.01) break;
        }
    }

    // 3) Austritt: zweite Brechung -> Umgebung erscheint auf dem Kopf (Kugellinse)
    vec3 Tout = refract(T, -P2, IOR);
    vec3 behind = environment(Tout);
    vec3 glassAbsorb = exp(-GLASS_ABS * sExit);
    vec3 transmitted = (scattered + trans * (1.0 - F) * behind) * glassAbsorb;

    vec3 col = F * reflected + (1.0 - F) * transmitted;
    col = toSRGB(softClip(col * 1.05));
    return vec4(col * g, g);                                  // vormultipliziertes Alpha
}

void main() {
    float unit = uRes.x / 960.0;                              // Gerätepixel pro SVG-Einheit
    vec2 pos = (gl_FragCoord.xy - uRes * 0.5) / unit;
    // Keine Spiegelung nötig: glReadPixels liefert die unterste Zeile zuerst, JavaFX erwartet die oberste
    // zuerst – dadurch zeigt pos.y im fertigen Bild bereits nach unten wie im SVG.
    float d = length(pos);
    float px = 1.0 / unit;                                    // eine Gerätepixelbreite in SVG-Einheiten
    float h = uHover;

    float width = SVG_RING * (1.0 - h);                       // Outline blendet aus, der Glasrand übernimmt
    float radius = OUTER_R - width + min(6.0, width * 0.5);  // Murmel reicht leicht unter die Outline

    vec4 color = vec4(0.0);                                   // transparent, vormultipliziert
    if (h > 0.0 && d < radius + px) {
        vec2 q = pos / radius;
        float edgeAA = clamp((radius - d) / px + 0.5, 0.0, 1.0);
        color = litMarble(q, h, uTime, edgeAA, radius * unit);
    }

    // Outline (Normalzustand: exakt der SVG-Ring r = 320..400)
    if (width > 0.3) {
        float ring = clamp((OUTER_R - d) / px + 0.5, 0.0, 1.0) * clamp((d - (OUTER_R - width)) / px + 0.5, 0.0, 1.0);
        color = color * (1.0 - ring) + vec4(uLine, 1.0) * ring;
    }

    fragColor = color;
}
