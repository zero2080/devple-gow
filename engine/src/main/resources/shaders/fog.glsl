// 안개 공용 (16 문서 §1.2, 31 문서 §3.1) — basic.frag · particle.frag · lensflare.frag 가 #include 한다 (Shader.source).
// 원작 안개는 층 2 칸 5 노드 (렌더 0x1281C8) 의 후처리다: Z 버퍼 → 프레임 알파 (0x127918 / 0x1275E0) × Fog_A·키 알파 (0x126CF8),
// 배경 (Z 지움) 은 BackPlane_A (0x127FF0), 그 위에 안개색 스프라이트 0x127000 (ALPHA 0x54 = (Cs−Cd)·Ad + Cd, ZTST GREATER Z 0x807FFF).
// 지오메트리는 조각 자신의 깊이로 같은 계수를 셰이더 안에서 건다 (basic.frag). 깊이를 쓰지 않는 반투명 패스 (파티클 등) 는 그 화소의
// 장면 깊이 (uSceneDepth = 깊이 사본, SceneFog) 로 계수를 구한다 — 안개 패스가 그 위에 깔리는 것과 같다.
// 유니폼을 설정하지 않으면 uFogMode = 0 = 끔.
uniform int uFogMode;       // 0 끔, 1 SCR_Fog, 2 SCR_LayeredFog
uniform int uFogBlend;      // 0 블렌드, 1 가산, 2 감산 (GS 0x54 / 0x58 / 0x52)
uniform vec4 uFogColor;     // 단일 안개 색 (a = 최대 불투명도) / 층 안개 색 0
uniform vec4 uFogColor1;    // 층 안개 색 1
uniform vec4 uFogColor2;    // 층 안개 색 2
uniform vec3 uFogStops;     // 층 안개 색 위치 (화면 위 0 → 아래 1)
uniform vec4 uFogDepth;     // x = 16·CentreZ·n0 (원작 Z 배율), y = near, z = far (선형화용), w = 배경 알파 (BackPlane_A/128, <0 끔)
uniform vec2 uFogGs;        // 원작 뷰포트 far f0, near n0 (Z24 = 0x800000 + z', z' = x·(f0−d)/((f0−n0)·d))
uniform vec2 uFogBand;      // 층 안개: 화면 위/아래의 고도각 눈금 t (원작 0x1208C8, 지평선 0.5). 같으면 화면 높이 비율로
uniform vec2 uFogScreen;    // 프레임버퍼 높이 (px), 배경 그리기 중이면 y = 1
uniform sampler2D uFogTex;  // 안개 텍스처 (MAT_defFogMat 등)
uniform int uFogTexOn;
uniform vec4 uFogTexXf;     // 스크롤 u, v (누적), 회전 (라디안, 누적), 배율
uniform float uFogWidth;    // 프레임버퍼 폭 (px)
uniform sampler2D uSceneDepth; // 장면 깊이 사본 (안개 앞 반투명 패스, SceneFog) — 창 깊이 [0, 1], 1 = 지움 (배경)
uniform float uFogBack;     // 장면 깊이가 배경인 화소의 계수 (FxEnvironment.backgroundFactor, 없으면 0)

vec4 layerColor(float t) {
	if (t <= uFogStops.x) {
		return uFogColor;
	}
	if (t <= uFogStops.y) {
		return mix(uFogColor, uFogColor1, (t - uFogStops.x) / max(uFogStops.y - uFogStops.x, 1e-4));
	}
	if (t <= uFogStops.z) {
		return mix(uFogColor1, uFogColor2, (t - uFogStops.y) / max(uFogStops.z - uFogStops.y, 1e-4));
	}
	return uFogColor2;
}

// 이 화소의 안개: rgb = 안개색 (텍스처 레이어 포함), a = 블렌드 계수 (원작 Ad/0x80). zw = 창 깊이,
// kBg ≥ 0 이면 깊이 대신 그 배경 계수 (BackPlane / 배경 화소).
vec4 fogAt(float zw, float kBg) {
	float n = uFogDepth.y, f = uFogDepth.z;
	float zn = zw * 2.0 - 1.0;
	float d = 2.0 * n * f / (f + n - zn * (f - n));
	float sy = 1.0 - gl_FragCoord.y / max(uFogScreen.x, 1.0);
	vec4 fc = uFogMode == 2 ? layerColor(uFogBand.x != uFogBand.y ? mix(uFogBand.x, uFogBand.y, sy) : sy) : uFogColor;
	// 원작 안개 패스 (16 문서 §1.2): Z24 = 0x800000 + z' → 0x127918 이 알파 A8 = 128 − ⌊z'/256⌋ (z' < 256 은 127),
	// 안개색 스프라이트(0x127000)는 Z = 0x807FFF GREATER 검사라 z' ≥ 0x7FFF (가까운 화소) 에는 안 그린다.
	// 계수 = A8·Fog_A / 0x80 (0x126CF8 변조, 0x54 블렌드) ≈ Fog_A·(1 − z'/32768).
	float f0 = max(uFogGs.x, uFogGs.y + 1.0), n0 = max(uFogGs.y, 1e-3);
	float zp = uFogDepth.x * max(f0 - d, 0.0) / ((f0 - n0) * max(d, 1e-3));
	float g = floor(zp / 256.0);
	float a8 = zp >= 32767.0 ? 0.0 : (g >= 1.0 ? 128.0 - g : 127.0);
	float k = kBg >= 0.0 ? kBg : a8 / 128.0;
	float a = fc.a * k;
	if (uFogTexOn != 0) {
		// 화면 공간 사각형: 중심 기준 배율·회전 후 스크롤 (GS MODULATE → ×2)
		vec2 uv = gl_FragCoord.xy / vec2(max(uFogWidth, 1.0), max(uFogScreen.x, 1.0)) - 0.5;
		float cs = cos(uFogTexXf.z), sn = sin(uFogTexXf.z);
		uv = mat2(cs, sn, -sn, cs) * uv / max(uFogTexXf.w, 1e-3) + 0.5 + uFogTexXf.xy;
		fc.rgb *= 2.0 * texture(uFogTex, uv).rgb;
	}
	return vec4(fc.rgb, a);
}

// 장면 깊이 사본으로 본 이 화소의 안개 — 원작 안개 패스가 깊이를 쓰지 않는 파티클·반투명 화소에 거는 것 (뒤 장면 Z 의 계수).
vec4 fogScene() {
	float zw = texelFetch(uSceneDepth, ivec2(gl_FragCoord.xy), 0).r;
	return fogAt(zw, zw >= 1.0 ? uFogBack : -1.0);
}
