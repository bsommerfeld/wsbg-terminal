#version 410 core
out vec4 fragColor;

// Der Sturm des Orbs als flache Fläche statt als Glaskugel: gleiche Rauschfelder,
// gleiche Verwirbelung, gleiche Wolken wie stormAt() in orb.frag, nur ohne Kugel
// und ohne Volumen. Die Form schneidet der Aufrufer zu (MarbleSheet#setShape) -
// hier ist die Fläche überall voll.

uniform sampler2D uNoise;
uniform vec2  uRes;        // Größe in Gerätepixeln
uniform float uTime;
uniform float uHover;      // Eingießen: wie weit der Sturm drin ist (0 leer, 1 voll);
                           // Ausbreiten: wie weit die Welle gelaufen ist (0 bis 1)
uniform float uMode;       // 0 eingießen, 1 als Welle auf dem Grund ausbreiten
uniform sampler2D uDistance; // Welle: Abstand zur Form, von der sie ausläuft (logische Pixel)
uniform float uReach;      // Welle: der größte Abstand darin - so weit läuft sie
uniform vec3  uPal[4];     // Farbverlauf des Sturms: tief -> dunkel -> mittel -> hell
uniform vec3  uCloud;      // Wolkenfarbe

const float TEXN = 256.0;
const float SWIRL = 0.12;
const float FLOW_SPEED = 0.035;
const float CLOUD_SPEED = 0.09;
const float SPAN = 1.0;    // Rauschtextur-Breiten über die Fläche - so viel zeigt auch der Orb
// Der tiefste Ton ist fast der Raum; ungehoben verschwinden ganze Stücke der Form.
const float FLOOR = 0.25;

vec4 noise(vec2 uv) { return texture(uNoise, uv + 0.5 / TEXN); }

vec3 palette(float t) {
    if (t < 0.45) return mix(uPal[0], uPal[1], t / 0.45);
    if (t < 0.70) return mix(uPal[1], uPal[2], (t - 0.45) / 0.25);
    return mix(uPal[2], uPal[3], (t - 0.70) / 0.30);
}

vec3 toLinear(vec3 c) { return pow(c, vec3(2.2)); }
vec3 toSRGB(vec3 c) { return pow(max(c, 0.0), vec3(1.0 / 2.2)); }

// Das Eingießen, wie das Füllen der Murmel in orb.frag: Nebel steigt von unten,
// eine Wolkenfront kommt von außen, beide treffen sich in der Mitte.
// q = Position auf der Fläche (-1..1 über die kürzere Seite, y nach unten).
float pour(vec2 q, float p, float t) {
    float r = length(q);
    float a = atan(q.y, q.x);
    float nf = clamp((noise(q * 0.3 + vec2(0.17, 0.41)).r - 0.25) / 0.5, 0.0, 1.0);
    float wave = 0.035 * sin(q.x * 7.0 + t * 5.0) + 0.02 * sin(q.x * 13.0 - t * 7.0);
    float mist = clamp((q.y - (1.31 - p * 2.18 + (nf - 0.5) * 0.5 + wave)) / 0.12, 0.0, 1.0);
    float edge = 1.28 - p * 1.42 + (nf - 0.5) * 0.45
               + 0.05 * sin(3.0 * a + r * 6.0 - t * 3.0) * smoothstep(0.0, 0.45, r);
    float front = clamp((r - edge) / 0.12, 0.0, 1.0);
    return max(max(mist, front), smoothstep(0.9, 1.0, p));
}

// Die Welle, wenn der Sturm sich auf dem Grund ablegt: sie startet an der Umrisslinie
// der Form (uDistance: Abstand jedes Punkts zur Form, in logischen Pixeln) und läuft
// als deren Parallele nach außen - erst in ihrer Gestalt, mit dem Abstand immer
// runder. Vorn eine helle Krone, dahinter ein schmaler Saum, der gleich versickert.
// Der Lauf ist ease-in-out: erst kriecht sie aus der Umrisslinie - lange genug, dass
// man die Form in ihr sieht -, dann läuft sie hinaus und rollt aus.
// MarbleSheet.waveRadius rechnet dasselbe, damit die Punkte im Takt der Krone leuchten.
float waveEdge(float p) { return p * p * (3.0 - 2.0 * p) * uReach; }

vec2 wave(vec2 uv, float p, float t) {
    float d = texture(uDistance, uv).r;
    float nf = noise(uv * 0.8 + vec2(0.17, 0.41) + t * 0.01).r;
    float edge = waveEdge(p) + (nf - 0.5) * 28.0;
    float inside = clamp((edge - d) / 24.0, 0.0, 1.0);
    float soak = smoothstep(edge - 130.0, edge - 16.0, d);
    float crest = exp(-pow((d - edge + 10.0) / 12.0, 2.0));
    // Erst mit dem Aufsetzen da, am Ende ist alles eingesickert.
    float thin = smoothstep(0.0, 0.04, p) * (1.0 - smoothstep(0.3, 1.0, p));
    return vec2(max(inside * soak * 0.28, crest * 0.6) * thin, crest * thin);
}

void main() {
    // Keine Spiegelung nötig: glReadPixels liefert die unterste Zeile zuerst, JavaFX
    // erwartet die oberste zuerst - gl_FragCoord.y zeigt im fertigen Bild nach unten.
    vec2 uv = gl_FragCoord.xy / uRes.x;                      // 0..1 über die Breite
    vec2 q = (gl_FragCoord.xy - uRes * 0.5) / (0.5 * min(uRes.x, uRes.y));
    float t = uTime;

    float lon = (uv.x - 0.5) * SPAN;
    float lat = (uv.y - 0.5 * uRes.y / uRes.x) * SPAN;
    float wu = noise(vec2(lon * 0.8 + t * 0.02, lat * 0.8 - t * 0.013)).g - 0.5;
    float wv = noise(vec2(lon * 0.8 - t * 0.017, lat * 0.8 + t * 0.021)).b - 0.5;
    float base = noise(vec2(lon + wu * SWIRL * 2.0 + t * FLOW_SPEED, (lat + wv * SWIRL) * 1.6)).r;
    float cl = noise(vec2(lon * 1.2 + wu * SWIRL * 3.0 + t * CLOUD_SPEED, lat * 1.8 + wv * SWIRL * 2.0)).a;
    float cloud = smoothstep(0.48, 0.78, cl);

    base = FLOOR + (1.0 - FLOOR) * base;
    vec3 albedo = mix(toLinear(palette(base)), toLinear(uCloud), cloud * 0.9);
    // wie in orb.frag: Sättigung so kräftig wie auf der Murmel
    albedo = max(mix(vec3(dot(albedo, vec3(0.2126, 0.7152, 0.0722))), albedo, 1.35), 0.0);

    float fill;
    if (uMode > 0.5) {
        vec2 w = wave(gl_FragCoord.xy / uRes, uHover, t);
        fill = w.x;
        albedo = mix(albedo, toLinear(uCloud), w.y * 0.5);  // die Krone fängt Licht
    } else {
        fill = pour(q, uHover, t);
    }
    fragColor = vec4(toSRGB(albedo) * fill, fill);           // vormultipliziert
}
