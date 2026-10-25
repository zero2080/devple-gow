package devple.gow.tools.level;

import devple.gow.tools.anim.Mat4;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 레벨 WAD 하나의 배치 / 충돌 / 볼륨 / 카메라를 모은 것 (GL 비의존, 엔진·도구 공용).
 */
public final class LevelData {

	/**
	 * 배치된 볼륨: 볼륨 + 소유 오브젝트 관절 0 월드 행렬 (world · inverse).
	 * <p>ball/hull 조각은 저마다 소유 GO 의 관절에 붙는다 (BallHull off[6] = {@code hullJoint}, ball 은 {@code ballJoint}). 원작 충돌 월드 (0x29C978) 는
	 * CDV·ENV·CMV 를 같은 조각 구조로 올리고 영웅 볼륨 판정 (0x1A7FFC → 0x11E190, 콜백 0x1A8508) 도 그 세계를 질의하므로 조각마다 GO 관절 행렬로 판정한다
	 * (CDV 쪽은 38 §4 [확실]). 데이터가 같은 것을 요구한다: ATHN05D 엘리베이터 ENV 의 PlayerVolume0 은 관절 2 (pressPlate) 에 붙은 r 32 구라 관절 0
	 * (승강로 바닥 y 713.6) 으로 두면 중심이 발판 윗면 (870) 보다 150 아래 (731) — 발판에 선 영웅이 영영 들지 않아 엘리베이터가 내려가지 않는다 (43 §21) [확실].
	 * 전 레벨 21 볼륨 (ENV 15 · CMV 4 · SEV 2) 에 관절 0 이 아닌 조각이 있다.</p>
	 * partInverse[j] = 관절 j 월드의 역 — 관절 0 이 아닌 조각이 쓰는 관절만 (없으면 null = 모든 조각이 관절 0 → {@link #inverse}).
	 * 자세가 바뀌면 (애니·부모 링크) {@link #pose} 로 그 자리에서 고친다.
	 */
	public record PlacedVolume(BallHull hull, DebugWire wire, String owner, float[] world, float[] inverse, float[][] partInverse) {

		public PlacedVolume(BallHull hull, DebugWire wire, String owner, float[] world, float[] inverse) {
			this(hull, wire, owner, world, inverse, null);
		}

		/** 관절 월드 (배치 자세) → 관절 0 이 아닌 조각의 관절 역행렬 표 (그런 조각이 없으면 null). */
		public static float[][] partInverses(BallHull h, float[][] jointWorld) {
			float[][] out = null;
			for (int pass = 0; pass < 2; pass++) {
				int[] js = pass == 0 ? h.hullJoint : h.ballJoint;
				int n = pass == 0 ? h.hulls.size() : h.balls.size();
				for (int i = 0; i < n && i < js.length; i++) {
					int j = js[i];
					if (j <= 0 || j >= jointWorld.length) {
						continue;
					}
					if (out == null) {
						out = new float[jointWorld.length][];
					}
					if (out[j] == null) {
						out[j] = Mat4.invertAffine(jointWorld[j]);
					}
				}
			}
			return out;
		}

		/** 소유 GO 자세가 바뀜 (Animator 애니·부모 링크): 관절 0 → world·inverse, 조각 관절 → partInverse (같은 배열을 고친다 — 스크립트 엔티티가 이 볼륨을 쥐고 있다). */
		public void pose(float[][] jointWorld) {
			if (jointWorld == null || jointWorld.length == 0) {
				return;
			}
			System.arraycopy(jointWorld[0], 0, world, 0, 16);
			System.arraycopy(Mat4.invertAffine(jointWorld[0]), 0, inverse, 0, 16);
			if (partInverse != null) {
				for (int j = 1; j < partInverse.length && j < jointWorld.length; j++) {
					if (partInverse[j] != null) {
						partInverse[j] = Mat4.invertAffine(jointWorld[j]);
					}
				}
			}
		}

		/** 조각 관절 j 의 로컬 점. */
		private float[] local(int j, float x, float y, float z) {
			float[] m = partInverse != null && j > 0 && j < partInverse.length && partInverse[j] != null ? partInverse[j] : inverse;
			float[] l = new float[3];
			Mat4.transformPoint(m, x, y, z, l);
			return l;
		}

		/** 월드 좌표 점이 볼륨(ball 또는 hull) 안인지. */
		public boolean contains(float x, float y, float z) {
			if (partInverse != null) {
				return hull.contains(j -> local(j, x, y, z));
			}
			float[] l = new float[3];
			Mat4.transformPoint(inverse, x, y, z, l);
			return hull.contains(l[0], l[1], l[2]);
		}

		/** 월드 좌표 점을 포함하는 ball/hull 의 영역 번호들. */
		public java.util.Set<Integer> regions(float x, float y, float z) {
			if (partInverse != null) {
				return hull.regionsAt(j -> local(j, x, y, z));
			}
			float[] l = new float[3];
			Mat4.transformPoint(inverse, x, y, z, l);
			return hull.regionsAt(l[0], l[1], l[2]);
		}

		/**
		 * 영역 ID 가 있는 엔티티(원본 0x4A ≠ 0)의 볼륨 판정: 그 영역의 ball/hull 만.
		 * 볼륨에 그 영역이 없을 때: 영역이 여럿으로 나뉜 볼륨(예 ATHN01E ENV_bossEnt 51–54 의 "boss zone 5/6" 55/56)이면 밖,
		 * 영역이 하나뿐이면 볼륨 전체 (전 레벨 진입/이탈 센서 580 개 중 6 개, 데이터 불일치로 보고 기존 동작 유지).
		 */
		public boolean contains(float x, float y, float z, int region) {
			if (region == 0) {
				return contains(x, y, z);
			}
			if (!hull.hasRegion(region)) {
				return hull.regionCount() < 2 && contains(x, y, z);
			}
			if (partInverse != null) {
				return hull.contains(j -> local(j, x, y, z), region);
			}
			float[] l = new float[3];
			Mat4.transformPoint(inverse, x, y, z, l);
			return hull.contains(l[0], l[1], l[2], region);
		}

		/** 월드 좌표 점을 포함하는 hull 번호 (-1 없음). */
		public int find(float x, float y, float z) {
			if (partInverse != null) {
				return hull.find(j -> local(j, x, y, z));
			}
			float[] l = new float[3];
			Mat4.transformPoint(inverse, x, y, z, l);
			return hull.find(l[0], l[1], l[2]);
		}
	}

	/**
	 * 배치된 카메라: 월드 행렬(행 0..2 = 오른쪽/위/뒤, 행 3 = 위치, 소유 GO 의 배치 자세 기준), 레일(NCV_, 월드 좌표, 없으면 null),
	 * joint = 붙은 소유 GO 관절 ({@link CameraDef.Params#joint}, 범위 밖이면 0).
	 */
	public record PlacedCamera(CameraDef def, String owner, float[] world, NcvCurve rail, int joint) {

		public float[] eye() {
			return new float[]{world[12], world[13], world[14]};
		}

		/** 바라보는 방향 = -Z 행 (정규화). */
		public float[] forward() {
			float x = -world[8], y = -world[9], z = -world[10];
			float l = (float) Math.sqrt(x * x + y * y + z * z);
			return new float[]{x / l, y / l, z / l};
		}
	}

	public final WadFile wad;
	public final LevelLayout layout;
	public final List<CollisionSheet> sheets = new ArrayList<>();
	public final Map<String, BallHull> hulls = new LinkedHashMap<>();
	public final Map<String, DebugWire> wires = new LinkedHashMap<>();
	public final Map<String, CameraDef> cameras = new LinkedHashMap<>();
	/** 레일 커브 NCV_ (월드 좌표, MESH_EXTRA 0x70). */
	public final Map<String, NcvCurve> curves = new LinkedHashMap<>();
	public final List<PlacedVolume> volumes = new ArrayList<>();
	public final List<PlacedCamera> placedCameras = new ArrayList<>();
	/** GO 인스턴스 이름 → 줄 설정 (SCR_RopeSkinned). */
	public final Map<String, RopeDef> ropes = new LinkedHashMap<>();
	/** GO 인스턴스 이름 → 비헤이비어 (IO_CSM / PushPull / IO_TandF / Breakable). */
	public final Map<String, GoBehavior> behaviors = new LinkedHashMap<>();
	/**
	 * GO 인스턴스 이름 → 화살 이미터 (비헤이비어 "Creator", 템플릿 = /TweakTemplates/ArrowEmitter/&lt;n&gt;/). 한 GO 에 다른 비헤이비어와 같이 있을 수 있다
	 * (ATHN02C gobiggun1crank = PushPull 202 + Creator 200, PAND03G gologlever = PushPull 15 + Creator 100).
	 */
	public final Map<String, Integer> arrowEmitters = new LinkedHashMap<>();

	public LevelData(WadFile wad) {
		this.wad = wad;
		this.layout = new LevelLayout(wad);
		String lastGo = null;
		for (WadTag t : wad.tags()) {
			if (t.tag() == WadTagType.MESH_EXTRA && t.hasData() && NcvCurve.is(t.name())) {
				curves.putIfAbsent(t.name(), NcvCurve.parse(t.name(), t.data()));
				continue;
			}
			if (t.tag() != WadTagType.RESOURCE || !t.hasData()) {
				continue;
			}
			ByteBuffer d = t.data();
			// GO CXT 바로 뒤 (같은 그룹) 의 SCR/BHV 는 그 GO 의 설정
			if (t.magic() == GoInstance.MAGIC && d.limit() == 0x5C) {
				lastGo = t.name().trim();
				continue;
			}
			if (lastGo != null && RopeDef.is(t.name(), d)) {
				ropes.putIfAbsent(lastGo, RopeDef.parse(lastGo, d));
				continue;
			}
			if (lastGo != null && t.magic() == 0x17 && GoBehavior.is(d)) {
				// GO 하나에 비헤이비어가 여럿일 수 있다 (PAND03G gologlever = Creator 100 + PushPull 15 "BMI Log Puller" id 351):
				// Creator 는 상호작용이 아니므로 다음 비헤이비어가 대신한다
				GoBehavior nb = GoBehavior.parse(lastGo, d);
				if (nb.type().equals("Creator")) {
					// 화살 이미터: 이벤트 1020 → 비헤이비어 정의 +4 (= BHV +0x20 템플릿) 번 ArrowEmitter 발사 (0x267698 → 0x231590 → 0x222710) [확실]
					arrowEmitters.putIfAbsent(lastGo, nb.template());
				}
				GoBehavior old = behaviors.get(lastGo);
				if (old == null || (old.type().equals("Creator") && !nb.type().equals("Creator"))) {
					behaviors.put(lastGo, nb);
				}
				continue;
			}
			if (t.magic() == 0x11) {
				if (CollisionSheet.is(d)) {
					sheets.add(new CollisionSheet(t.name(), d));
				} else if (BallHull.is(d)) {
					hulls.putIfAbsent(t.name(), new BallHull(t.name(), d));
				} else if (DebugWire.is(d)) {
					wires.putIfAbsent(t.name(), new DebugWire(t.name(), d));
				}
			} else if (t.magic() == CameraDef.MAGIC && t.size() >= 0xD0) {
				cameras.putIfAbsent(t.name(), CameraDef.parse(t.name(), d));
			}
		}
		for (LevelLayout.Placement p : layout.placements()) {
			float[] w = p.jointWorld()[0];
			for (String r : p.object().refs()) {
				BallHull h = hulls.get(r);
				if (h != null) {
					DebugWire dw = wires.get(r.substring(0, 2) + "Z" + r.substring(3));
					volumes.add(new PlacedVolume(h, dw, p.instance().name(), w.clone(), Mat4.invertAffine(w),
							PlacedVolume.partInverses(h, p.jointWorld())));
				}
				CameraDef c = cameras.get(r);
				if (c != null) {
					// 카메라 노드는 CAM 헤더 +0x08 의 관절에 붙는다 (0x12FEE0 → 0x1300C8 노드 +0x60, 월드 0x1303D0 = 로컬 × 관절 행렬) [확실].
					// 대개 0 (루트) 이고 IdleCam 류는 1 (= 카메라 이름의 관절). 예전 엔진은 "카메라 이름과 같은 관절, 없으면 루트" 로 골라 전 WAD 2916 개 중
					// 19 개가 틀렸다 — PAND00C Crank1cam (RingRot) 은 고리 중심이 아니라 크랭크 GO 뿌리에 붙어 눈이 고리 바깥 633 (z 2586) 이었다 (43 §36).
					// 범위 밖 (PAND03G gologhitcamgrp 관절 1 개에 56) 은 루트 [근사: 원작은 표를 넘어 읽는다].
					int j = c.p().joint();
					if (j < 0 || j >= p.jointWorld().length) {
						j = 0;
					}
					float[] jw = p.jointWorld()[j];
					// 레일은 월드 좌표 그대로 (ATHN01A Boat1Cam1 의 월드 위치가 NCV_Boat1Rail1 위에 정확히 놓인다)
					placedCameras.add(new PlacedCamera(c, p.instance().name(), Mat4.mul(c.local(), jw),
							c.rail() != null ? curves.get(c.rail()) : null, j));
				}
			}
		}
	}

	private java.util.Set<String> animatorOwners;

	/**
	 * 소유 ESC 에 모드 1 Animator (타입 5) 가 있는 GO 인스턴스 이름 (ScriptWorld 와 같은 소유자 규칙: ESC 를 참조하는 첫 배치).
	 * 원작 Animator 메시지 0 (등록, 0x1AAFE8 → 0x1AB000–0x1AB01C) 은 모드 1 이면 소유 GO +0xF8 = 2 로 덮어써 비트 0 을 끈다 [확실: 코드] —
	 * GO 컴포넌트 갱신 0x130F48 은 +0xF8 비트 0 이 꺼진 GO 의 애니 컴포넌트를 돌리지 않으므로 (0x130F88–0x130F94) 시작 act 가 반복 act 라도
	 * 스크립트가 틀 때까지 시작 자세에 멈춰 있다. 타입 6 (Animator 변형) 의 메시지 처리 0x1AB740 은 비어 있다.
	 */
	public java.util.Set<String> animatorOwners() {
		if (animatorOwners == null) {
			java.util.Set<String> out = new java.util.HashSet<>();
			for (WadTag t : wad.resources()) {
				if (t.magic() != devple.gow.tools.script.EscScript.MAGIC || !t.name().startsWith("ESC_")) {
					continue;
				}
				devple.gow.tools.script.EscScript s;
				try {
					s = new devple.gow.tools.script.EscScript(t.data());
				} catch (RuntimeException ex) {
					continue;
				}
				if (!s.target.equals("SCR_Entities")) {
					continue;
				}
				LevelLayout.Placement owner = layout.placements().stream().filter(p -> p.object().refs().contains(t.name())).findFirst().orElse(null);
				if (owner == null) {
					continue;
				}
				for (devple.gow.tools.script.EscScript.Entity e : s.entities) {
					if (e.type() == 5 && e.param() == 1) {
						out.add(owner.instance().name());
					}
				}
			}
			animatorOwners = out;
		}
		return animatorOwners;
	}

	/**
	 * 배치 GO 의 애니 컴포넌트가 매 프레임 스스로 도는가 (반복 시작 act 가 저절로 재생되는가) [확실: 코드]:
	 * GO 생성 0x1307C8 이 +0xF8 = 인스턴스 플래그 (메모리 레코드 +0x26 = 파일 0x1E — 시작 시간 0x58 / act 0x5C 가 파일 0x50 / 0x54 인 것과 같은 +8) 로 두고
	 * (0x130820–0x130850), 모드 1 Animator 가 등록 때 2 로 덮어쓴다 ({@link #animatorOwners}). 갱신 0x130F48 은 비트 0 이 켜진 GO 만 애니를 돌린다.
	 * 예: DEST03 gosandwedge (플래그 1 + sandWedge-Animator1) — 뿔피리를 불 때까지 모래벽이 닫혀 있다 (영상 2716–2724), PAND00B goelevatorgrpa (플래그 0).
	 */
	public boolean selfAnimates(LevelLayout.Placement p) {
		return (p.instance().flags() & 1) != 0 && !animatorOwners().contains(p.instance().name());
	}

	/** 카메라 이름(CAM_ 없이, 대소문자 무시)으로 배치 카메라 찾기. CMV 이름표의 "RCM_x" 를 그대로 넘겨도 된다. */
	public PlacedCamera camera(String name) {
		String n = name.startsWith("RCM_") ? name.substring(4) : name.startsWith("CAM_") ? name.substring(4) : name;
		for (PlacedCamera c : placedCameras) {
			if (c.def().name().substring(4).equalsIgnoreCase(n)) {
				return c;
			}
		}
		return null;
	}

	/**
	 * 가시 칸 컨텍스트 (43 문서 §11, 0x152EC8 → 0x16D6F0 / 0x16DC60 / 0x16D6F8): 카메라 눈 위치로 시트마다 보이는 컨텍스트 마스크를 만들고,
	 * 시트의 컨텍스트 목록에 있는데 비트가 꺼진 컨텍스트의 인스턴스 (소문자 이름) 를 돌려준다. 목록에 없는 컨텍스트 (CXT_default 등) 는 그대로 보인다.
	 */
	public java.util.Set<String> contextHidden(float x, float y, float z) {
		return contextHidden(new float[][]{{x, y, z}});
	}

	/**
	 * 활성 뷰포트가 여럿일 때 (SCR_Mirror 거울 뷰 + 주 뷰, 43 문서 §12) [확실: 0x152EC8]: 시트 마스크 +0x150 은 프레임마다 한 번 0 (0x16D6F0) 이고
	 * 뷰 목록 순서대로 각 뷰 카메라 위치의 칸을 같은 마스크에 누적 (0x16DC60 — 뷰 마스크 인자는 레코드 적용 0x16D240 에서 쓰이지 않는다),
	 * 그 뒤 뷰마다 같은 마스크를 적용 (0x11EF98(뷰 +0x3AC) → 0x16D6F8). ⇒ 모든 뷰가 같은 숨김 집합.
	 *
	 * @param eyes 뷰 목록 순서의 눈 위치들
	 */
	public java.util.Set<String> contextHidden(float[][] eyes) {
		java.util.Set<String> hiddenCtx = new java.util.HashSet<>();
		for (CollisionSheet s : sheets) {
			long mask = 0;
			for (float[] e : eyes) {
				mask = s.visibleContextMask(e[0], e[1], e[2], mask);
			}
			for (int i = 0; i < s.visContexts.size() && i < 64; i++) {
				if ((mask & (1L << i)) == 0) {
					hiddenCtx.add(s.visContexts.get(i).toLowerCase(java.util.Locale.ROOT));
				}
			}
		}
		if (hiddenCtx.isEmpty()) {
			return java.util.Set.of();
		}
		java.util.Set<String> out = new java.util.HashSet<>();
		for (Map.Entry<String, String> e : layout.contextOf.entrySet()) {
			if (hiddenCtx.contains(e.getValue().toLowerCase(java.util.Locale.ROOT))) {
				out.add(e.getKey());
			}
		}
		return out;
	}

	/**
	 * 0x20D0D8 (SCR_Fog / SCR_LayeredFog 갱신 0x20D220): 소유 GO 의 컨텍스트가 CXT_default 면 참, 아니면 눈 위치를 포함하는 칸 중 하나가
	 * 그 컨텍스트를 형식 2 로 켜야 참 (0x16DE18). 가시 시트가 없으면 참.
	 */
	public boolean contextActive(String instance, float x, float y, float z) {
		String ctx = layout.contextOf.get(instance.toLowerCase(java.util.Locale.ROOT));
		if (ctx == null || ctx.equalsIgnoreCase("CXT_default") || sheets.isEmpty()) {
			return true;
		}
		for (CollisionSheet s : sheets) {
			if (s.cellListsContext(x, y, z, ctx)) {
				return true;
			}
		}
		return false;
	}

	/** 월드 점이 들어 있는 카메라 볼륨(CMV) → 활성 카메라. 없으면 null. */
	public PlacedCamera activeCamera(float x, float y, float z) {
		for (PlacedVolume v : volumes) {
			if (v.hull().kind != 1) {
				continue;
			}
			int h = v.find(x, y, z);
			if (h >= 0) {
				String n = v.hull().nameOf(h);
				PlacedCamera c = n != null ? camera(n) : null;
				if (c != null) {
					return c;
				}
			}
		}
		return null;
	}
}
