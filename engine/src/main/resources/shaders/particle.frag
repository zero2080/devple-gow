#version 410 core
in vec2 vUv;
in vec4 vColor;

uniform sampler2D uTex;
uniform int uUseTex;
uniform int uSoft;      // 1 = 텍스처 없을 때 원형 부드러운 점
uniform int uGs;        // 1 = 원작 PTC (GS 규칙: 정점색 0x80 = 1.0, 알파 As/0x80, 미리 곱한 색 출력)
uniform int uTfx;       // TEX0.TFX: 0 MODULATE, 1 DECAL (2/3 HIGHLIGHT 는 MODULATE 로)
uniform int uTcc;       // TEX0.TCC: 1 = 텍스처 알파 사용

out vec4 oColor;

void main() {
	if (uGs != 0) {
		// GS: MODULATE Cs = Ct·Cv/0x80, As = At·Av/0x80 (TCC=0 이면 Av), 텍스처 없으면 Cs = Cv (0..255 → 0x80 은 회색 0.5)
		// 블렌드 계수 As/0x80 (최대 ≈ 2) → 색에 미리 곱해 내보내고, 블렌드 함수는 ONE / ONE_MINUS_SRC_ALPHA, ONE, REVERSE_SUBTRACT
		vec3 rgb;
		float a;
		if (uUseTex != 0) {
			vec4 t = texture(uTex, vUv);
			if (uTfx == 1) {
				rgb = t.rgb;
				a = uTcc != 0 ? t.a : vColor.a;
			} else {
				rgb = min(t.rgb * vColor.rgb, vec3(1.0));
				a = uTcc != 0 ? t.a * vColor.a : vColor.a;
			}
		} else {
			rgb = min(vColor.rgb * (128.0 / 255.0), vec3(1.0));
			a = vColor.a;
		}
		a = clamp(a, 0.0, 255.0 / 128.0);
		if (a < 0.004) {
			discard;
		}
		oColor = vec4(min(rgb * a, vec3(1.0)), min(a, 1.0));
		return;
	}
	vec4 c = vColor;
	if (uUseTex != 0) {
		c *= texture(uTex, vUv);
	} else if (uSoft != 0) {
		vec2 d = vUv * 2.0 - 1.0;
		c.a *= clamp(1.0 - dot(d, d), 0.0, 1.0);
	}
	if (c.a < 0.004) {
		discard;
	}
	oColor = c;
}
