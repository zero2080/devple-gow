package devple.gow.tools.level;

import devple.gow.tools.anim.Mat4;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 게임 오브젝트 인스턴스 {@code go*} (CXT 서브타입 0x00020001, 0x5C 바이트). 레벨에 오브젝트(0x40001)를 하나 배치한다.
 * <pre>
 * 0x00 u32     magic 0x00020001
 * 0x04 char[24] 오브젝트 이름 (0x40001 리소스 이름, 소문자)
 * 0x1C u16     id (WAD 안 일련번호, EntityCount 와 연결)
 * 0x1E u16     flags (대부분 1, 0 = 비활성 시작 추정, 5)
 * 0x20 vec4    위치 (w=1)
 * 0x30 vec3    오일러 회전 (라디안, Rx·Ry·Rz) + w = 균일 스케일
 * 0x40 vec4    월드 경계 중심 (도구가 계산한 값, 배치에는 쓰지 않음)
 * 0x50 f32     시작 애니 시간 (초)  0x54 u16 시작 act 번호 (0x56 u16 = 같은 템플릿 안 번호)
 * 0x58 s32     부모 링크: −1 = 없음, 아니면 하위 16 비트 = 부모 GO id (0x1C), 상위 16 비트 = 부모 관절 번호
 * </pre>
 * 부모 링크 [확실: 데이터 — 전 레벨 68 건이 모두 "올라탄 것 → 탈것" 짝: ATHN02A goleverelev2mid·gosparkle → goelevator2mid 관절 1,
 * goshipgroup (정박한 배) → goshiptrans 관절 1 "Anim", PAND04G 줄·레버·소리 → gocrane 관절 2, PAND03A 불꽃 → gosac6loop 관절 1 …]
 * [추정: 원작 처리 경로 미해석]. 엔진은 부모 관절이 처음 자리에서 움직인 만큼 자식을 함께 옮긴다 ({@code nav.Platforms}).
 * 시작 act/시간: GO 리셋 0x1309C0 이 애니 컴포넌트에 act (번호 ≥ act 수면 0) 를 걸고 (0x1007C8), 시간 ≠ 0 이면 그 시간으로 옮긴다 (0x1005D0)
 * [확실: 코드 + 데이터 — ATHN08A fellergroup 은 act 1–3 · 시간 0–13 초로 나무 흔들림을 엇갈리게 둔다].
 * 월드 행렬 = Scale(w) · Rx · Ry · Rz · Translate(pos) (행벡터). 오브젝트 관절 월드 = 관절 로컬 · 이 행렬.
 */
public record GoInstance(String name, String object, int id, int flags, float[] pos, float[] rot, float scale,
						 float[] bound, float startTime, int startAct, int parentLink) {

	public static final int MAGIC = 0x00020001;

	public static GoInstance parse(String name, ByteBuffer d) {
		byte[] nb = new byte[24];
		d.get(4, nb);
		int len = 0;
		while (len < 24 && nb[len] != 0) {
			len++;
		}
		float[] p = {d.getFloat(0x20), d.getFloat(0x24), d.getFloat(0x28)};
		float[] r = {d.getFloat(0x30), d.getFloat(0x34), d.getFloat(0x38)};
		float[] b = {d.getFloat(0x40), d.getFloat(0x44), d.getFloat(0x48)};
		return new GoInstance(name, new String(nb, 0, len, StandardCharsets.ISO_8859_1), d.getShort(0x1C) & 0xFFFF,
				d.getShort(0x1E) & 0xFFFF, p, r, d.getFloat(0x3C), b,
				d.limit() >= 0x58 ? d.getFloat(0x50) : 0, d.limit() >= 0x56 ? d.getShort(0x54) & 0xFFFF : 0,
				d.limit() >= 0x5C ? d.getInt(0x58) : -1);
	}

	/** 부모 GO id (0x58 하위 16 비트), 없으면 −1. */
	public int parentId() {
		return parentLink == -1 ? -1 : parentLink & 0xFFFF;
	}

	/** 부모 관절 번호 (0x58 상위 16 비트), 없으면 −1. */
	public int parentJoint() {
		return parentLink == -1 ? -1 : parentLink >>> 16;
	}

	/** 인스턴스 월드 행렬 (행벡터, float[16]). */
	public float[] matrix() {
		float[] m = Mat4.fromEuler(rot[0], rot[1], rot[2]);
		for (int i = 0; i < 12; i++) {
			m[i] *= scale;
		}
		m[12] = pos[0];
		m[13] = pos[1];
		m[14] = pos[2];
		m[15] = 1;
		return m;
	}
}
