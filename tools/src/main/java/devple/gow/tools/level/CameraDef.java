package devple.gow.tools.level;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 카메라 CAM_ (CAM 서버 0x09, 0x24C 바이트). 소유 오브젝트 관절 로컬 좌표. 로더 0x13E790 이 0x70..0xCC 를 정의 객체 +0x10 으로 복사.
 * <pre>
 * 0x00 u32 9          0x08 u16 0, u16 0xFFFF (1 = ANM 으로 움직이는 관절 카메라: IdleCam 류 인트로 팬)
 * 0x10 vec4[3]        회전 행 (행벡터, 관절 스케일의 역수만큼 스케일됨 → 관절 월드를 곱하면 정규직교)
 * 0x40 vec4           위치 (w=1)  — 카메라는 로컬 -Z 를 바라본다
 * 0x50 char[24]       레일 커브 이름 NCV_x (첫 바이트 0 이면 레일 없음)
 * 0x70 f32  FOV (라디안, 가로 추정)         0x74 u32 플래그 (아래)
 * 0x78 u16 피치 모드   0x7A u16 요 모드      (0 패드 회전, 1 설계 각도 고정, 2 고정 위치에서 대상 조준, 3 레일 위 점에서 대상 조준)
 * 0x7C f32 거리 비율 c (1 = 기준점까지 거리 그대로)
 * 0x80/0x84 f32 레일 점-대상 거리 max/min (×16 유닛, 레일 탐색 비용)    0x88/0x8C 최종 거리 max/min (×16)
 * 0x90/0x94 f32 피치/요 허용 범위 (설계 각도 ± 도, 요 180 = 무제한)
 * 0x98..0xA4 미사용 쌍(로더가 min≤max 정렬)   0xA8 f32 레일 t 감쇠 (프레임당 남기는 비율)   0xAC f32 0.75 (청취자 위치 비율)
 * 0xB0 u32 필터 이름 인덱스    0xB8 f32 기본 전환 시간(초, 0 = 컷)    0xBC s32 우선순위 (큰 값 우선)
 * 0xC0 vec3 고정 주시점 (×16, 카메라 로컬; 플래그 0x40000 이고 0x100 이 없을 때)
 * 0xCC 전환표 12 × 32B: char[24] 이전 카메라 이름, f32 전환 시간, u32 동순위 우선 플래그
 * </pre>
 * 플래그 0x74: 0x100 대상 추적(궤도 카메라), 0x40000 고정 주시(0x100 없을 때), 0x200 레일 탐색에 각도 범위 반영,
 * 0x400 레일 접선 기준 각도, 0x800/0x4000 점프 중 대상 높이 고정, 0x2000 갱신 생략(일시), 0x10000 다중 대상 가중 평균,
 * 0x80000 청취자 거리 제한 해제, 0x100000 청취자 비율 = 0xAC. 근거: 0x1F3768, 0x1F29A0, 0x1F1AA0, 0x1EE8E0 (30 문서).
 */
public record CameraDef(String name, float[] local, String rail, int mode, int railKind, float[] params, float[] fov,
		Params p, List<Transition> transitions) {

	public static final int MAGIC = 0x00000009;
	public static final int F_TRACK = 0x100;
	public static final int F_RAIL_ANGLES = 0x200;
	public static final int F_FIXED_LOOK = 0x40000;
	public static final int F_HOLD_JUMP = 0x800 | 0x4000;

	/** 런타임 파라미터 (0x70.., 거리 단위는 이미 ×16 한 월드 유닛). */
	public record Params(float fovRad, int flags, int pitchMode, int yawMode, float distScale, float railMax, float railMin,
			float distMax, float distMin, float pitchRange, float yawRange, float damping, int priority, float blend,
			float[] lookOffset, boolean animated) {
	}

	/** 이 카메라로 들어올 때, 이전 카메라가 from 이면 blend 초 (기본값 대신). prefer = 동순위일 때 우선. */
	public record Transition(String from, float blend, boolean prefer) {
	}

	public static CameraDef parse(String name, ByteBuffer d) {
		float[] m = new float[16];
		for (int r = 0; r < 4; r++) {
			for (int c = 0; c < 4; c++) {
				m[r * 4 + c] = d.getFloat(0x10 + r * 16 + c * 4);
			}
		}
		m[3] = m[7] = m[11] = 0;
		m[15] = 1;
		String rail = d.get(0x50) != 0 ? cstr(d, 0x50, 24) : null;
		float[] pr = {d.getFloat(0x80), d.getFloat(0x84), d.getFloat(0x88), d.getFloat(0x8C)};
		float[] f = {d.getFloat(0x90), d.getFloat(0x94)};
		int pitchMode = d.getShort(0x78) & 0xFFFF, yawMode = d.getShort(0x7A) & 0xFFFF;
		// 로더 0x13E8A0: (0x80,0x84) (0x88,0x8C) 는 max ≥ min 이 되도록 고친다
		float railMax = d.getFloat(0x80), railMin = Math.min(d.getFloat(0x84), railMax);
		float distMax = d.getFloat(0x88), distMin = Math.min(d.getFloat(0x8C), distMax);
		Params p = new Params(d.getFloat(0x70), d.getInt(0x74), pitchMode, yawMode, d.getFloat(0x7C), railMax * 16,
				railMin * 16, distMax * 16, distMin * 16, d.getFloat(0x90), d.getFloat(0x94), d.getFloat(0xA8),
				d.getInt(0xBC), d.getFloat(0xB8), new float[]{d.getFloat(0xC0) * 16, d.getFloat(0xC4) * 16, d.getFloat(0xC8) * 16}, d.getShort(0x08) == 1);
		// 전환표: 끝에서부터 빈 이름을 버린다 (0x13E950)
		List<Transition> tr = new ArrayList<>();
		int n = 12;
		while (n > 0 && (0xCC + n * 0x20 > d.limit() || d.get(0xCC + (n - 1) * 0x20) == 0)) {
			n--;
		}
		for (int i = 0; i < n; i++) {
			int e = 0xCC + i * 0x20;
			tr.add(new Transition(cstr(d, e, 24), d.getFloat(e + 0x18), d.getInt(e + 0x1C) != 0));
		}
		return new CameraDef(name, m, rail, pitchMode, yawMode, pr, f, p, List.copyOf(tr));
	}

	private static String cstr(ByteBuffer d, int off, int max) {
		int len = 0;
		while (len < max && d.get(off + len) != 0) {
			len++;
		}
		byte[] b = new byte[len];
		d.get(off, b);
		return new String(b, StandardCharsets.ISO_8859_1);
	}

	/** RCM_/CAM_ 접두사 없는 이름. */
	public String shortName() {
		return name.startsWith("CAM_") ? name.substring(4) : name;
	}
}
