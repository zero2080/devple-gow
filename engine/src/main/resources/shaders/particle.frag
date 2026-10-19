#version 410 core
in vec2 vUv;
in vec4 vColor;

uniform sampler2D uTex;
uniform int uUseTex;
uniform int uSoft;      // 1 = 텍스처 없을 때 원형 부드러운 점

out vec4 oColor;

void main() {
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
