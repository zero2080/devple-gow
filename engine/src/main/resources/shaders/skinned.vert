#version 410 core
// GPU 스키닝. 원작 VU1 마이크로코드가 하던 2관절 블렌딩을 대체한다.
// 관절 행렬은 원본(행벡터 규약, row-major)을 그대로 업로드 → GLSL 에서는 전치로 해석되어 M * v 가 v·M 과 같다.
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec4 aColor;
layout(location = 4) in vec2 aJoints;
layout(location = 5) in float aWeight;

uniform mat4 uJoints[128];
uniform mat4 uModel;
uniform mat4 uViewProj;

out vec3 vNormal;
out vec2 vUv;
out vec4 vColor;

void main() {
	mat4 a = uJoints[int(aJoints.x)];
	mat4 b = uJoints[int(aJoints.y)];
	vec4 p = (a * vec4(aPos, 1.0)) * aWeight + (b * vec4(aPos, 1.0)) * (1.0 - aWeight);
	vec3 n = mat3(a) * aNormal * aWeight + mat3(b) * aNormal * (1.0 - aWeight);
	vNormal = mat3(uModel) * n;
	vUv = aUv;
	vColor = aColor;
	gl_Position = uViewProj * uModel * vec4(p.xyz, 1.0);
}
