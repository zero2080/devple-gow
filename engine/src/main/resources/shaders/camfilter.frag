#version 410 core
// 카메라 필터 (0x121128 / 0x1208C8, 45 문서 §1): u = 광선과 월드 위쪽의 각 / π, 화면 세로로 uRange.x (위) → uRange.y (아래) 선형.
// Toppoint 위 = 위 색, 사이 선형, BottomPoint 아래 = 아래 색. 알파 = 템플릿 A (GS A·128, 128 = 1.0). 블렌드는 호출 쪽 glBlend*.
in float vY;
out vec4 fragColor;

uniform vec2 uRange;
uniform vec3 uPoints;
uniform vec4 uTop;
uniform vec4 uMid;
uniform vec4 uBottom;

void main() {
	float u = mix(uRange.x, uRange.y, vY);
	vec4 c;
	if (u <= uPoints.x) {
		c = uTop;
	} else if (u < uPoints.y) {
		c = mix(uTop, uMid, (u - uPoints.x) / max(1e-6, uPoints.y - uPoints.x));
	} else if (u < uPoints.z) {
		c = mix(uMid, uBottom, (u - uPoints.y) / max(1e-6, uPoints.z - uPoints.y));
	} else {
		c = uBottom;
	}
	fragColor = vec4(clamp(c.rgb, 0.0, 1.0), clamp(c.a, 0.0, 255.0 / 128.0));
}
