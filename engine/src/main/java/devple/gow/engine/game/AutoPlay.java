package devple.gow.engine.game;

import devple.gow.engine.input.BotInput;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.engine.verify.TraversalVerifier;
import devple.gow.tools.level.LevelData;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * -Dgow.auto: 레벨 단위 자동 플레이 검증 (원작에 없음, 43 문서). 실제 게임 화면·물리·적 전투로 주 경로 한 구간을 진행한다.
 * <ol>
 *   <li>적이 {@link #FIGHT} 안에 있으면 {@link BotInput} 이 싸운다</li>
 *   <li>아니면 스크립트 볼륨 (진입/이탈 센서가 붙은 것) 을 가까운 순서로 걸어서 방문, {@link #WALK_LIMIT} 초 안에 못 가면 순간이동 (기록)</li>
 *   <li>마지막으로 {@code gow.auto.goal} (주 경로 전환 지점) 에서 R1·R2 연타·× 를 누르며 기다린다</li>
 * </ol>
 * {@code gow.auto.next} 가 아닌 레벨로의 전환은 막고 기록한다. 결과 한 줄을 {@code gow.auto.log} 에 덧붙이고 창을 닫는다.
 */
public final class AutoPlay {

	public static final boolean ON = System.getProperty("gow.auto") != null;
	/** gow.auto.novol: 볼륨 방문 없이 곧장 목표로. gow.auto.wait=초: 처음 그 시간 동안 입력 없음 (시작 연출 검증용). */
	static final boolean NO_VOLUMES = System.getProperty("gow.auto.novol") != null;
	static final float WAIT = Float.parseFloat(System.getProperty("gow.auto.wait", "0"));
	static final float FIGHT = 500f, WALK_LIMIT = 3f, DWELL = 1f, GOAL_WAIT = 90f, ARRIVE = 40f;
	/** 목표에서 기다리는 주기: 앞 6 초는 목표 위 (R1), 뒤 4 초는 마지막 방문 점으로 물러난다 — 목표 위에 서 있으면 보류되는 스크립트 (ATHN01A 해치). */
	static final float GOAL_CYCLE = 10f, GOAL_STAY = 6f;

	private final String level, next;
	private final float[] goal;
	/** gow.auto.face=x,y,z: 목표에서 바라볼 점 (손잡이 오브젝트 중심 등, 없으면 null). */
	private final float[] face = parse(System.getProperty("gow.auto.face"));

	private static float[] parse(String g) {
		if (g == null) {
			return null;
		}
		String[] q = g.split(",");
		return new float[]{Float.parseFloat(q[0]), Float.parseFloat(q[1]), Float.parseFloat(q[2])};
	}
	private final float timeout = Float.parseFloat(System.getProperty("gow.auto.secs", "300"));
	private final BotInput bot = new BotInput(0);
	private final List<float[]> todo = new ArrayList<>(), all = new ArrayList<>();
	/** 목표 근처 볼륨을 먼 것부터 다시 도는 중 (todo 를 순서대로). */
	private boolean revisit, revisited;
	private float[] cur, lastVisited;
	private float lastY = Float.NaN, lastX, lastZ;
	private float[] fallStart;
	private boolean fallLogged;
	private float legTime, dwell, time, goalTime, r1Clock;
	private boolean atGoal, done, teleportedThisCycle;
	private int teleports, visited, deaths, vetoes;
	/** 영웅 붉은 오브 수 (GameScene 이 매 틱 넣음, 기록용). */
	public int redOrbs;
	/** 영웅 정면 yaw (GameScene 이 매 틱 넣음) — 경로 단계 crank: 가 스틱을 영웅 정면으로 민다. */
	public float heroYaw;
	/** 영웅 MP (로그용, &lt; 0 = 모름). */
	public float mp = -1;
	/** 잡기를 기다리는 소품이 가까이 있음 (GameScene, ATHN01F 침대 쌍둥이): 목표에서 R2 대신 ○ (GRAB → MAIXPTBEDMOUNT). */
	public boolean grabCue;
	/** 싸우는 대상이 잡기를 기다림 (자세 태그 ≥ 0, 예: 선창 히드라 기절 루프 107 SET_1B0 830) → 콤보 대신 ○ 부터. */
	public boolean grabTarget;
	/** 대상이 석상 (석화된 적) — ○ 잡기 묶음 없이 □ 묶음만 (BotInput.stoneTarget). */
	public boolean stoneTarget;
	/**
	 * 메두사의 시선으로 대상을 석화할 때 (GameScene: 시선 보유 + MP ≥ {@link #GAZE_MP} + 대상 동결 게이지 &lt; 60000 이고 아직 돌이 아님) —
	 * ATHN02D 메시지 595 "전진하려면 모든 미노타우르스를 얼린 뒤 부숴야" (EventSmash1 1046 만 센다). 석화된 대상은 보통 묶음 □ 로 부순다.
	 */
	public boolean gaze;
	/** 시선이 선택 마법이 아님 (← 로 고른다). */
	public boolean selectGaze;
	/** 아르테미스의 검을 보유했는데 들지 않았음 (GameScene 이 매 틱, -Dgow.auto.artemis 일 때만) → 싸울 때 L1+R1 로 든다 [엔진 전용 봇, 43 §34]. */
	public boolean artemis;
	public static final boolean USE_ARTEMIS = System.getProperty("gow.auto.artemis") != null;
	/** 시선을 시작할 최소 MP (단계 0 Drain 12.5/초 × 미노타우로스 저항 6000 / 6000/초 ≈ 1 초, 여유 포함). */
	public static final float GAZE_MP = 15f;
	private final Set<String> vetoed = new LinkedHashSet<>();

	public AutoPlay(String level) {
		this.level = level;
		this.next = System.getProperty("gow.auto.next", "");
		String g = System.getProperty("gow.auto.goal");
		if (g != null) {
			String[] q = g.split(",");
			goal = new float[]{Float.parseFloat(q[0]), Float.parseFloat(q[1]), Float.parseFloat(q[2])};
		} else {
			goal = null;
		}
	}

	/** 방문할 볼륨 점 (진입/이탈 센서가 붙은 볼륨, 가까운 순 — 매번 현재 위치에서 가장 가까운 것). */
	public void plan(ScriptWorld world, LevelCollision col) {
		Set<LevelData.PlacedVolume> vols = new LinkedHashSet<>();
		for (ScriptEntity e : world.entities()) {
			if (e.volume != null && (e.type() == 0 || e.type() == 1)) {
				vols.add(e.volume);
			}
		}
		for (LevelData.PlacedVolume v : vols) {
			float[] p = TraversalVerifier.interior(v, col);
			if (p != null && p[3] == 0 && !NO_VOLUMES) {
				todo.add(p);
				all.add(p);
			}
			log("볼륨 " + v.owner() + " " + (p == null ? "점 없음" : fmt(p) + (p[3] != 0 ? " 공중 (건너뜀)" : "")));
		}
		log("계획: 볼륨 " + todo.size() + ", 목표 " + (goal == null ? "없음" : fmt(goal)) + " → " + next);
	}

	/** 틱마다: 입력을 정하고, 순간이동이 필요하면 hero 위치를 바꾼다. */
	public BotInput steer(Vector3f hero, Vector3f enemy, boolean bossActive, float camYaw, float dt) {
		time += dt;
		if (time < WAIT) { // 시작 연출 동안 손대지 않음 (ATHN02A 배 입항 10.4 초)
			return bot.press(null);
		}
		if (!Float.isNaN(lastY) && hero.y < lastY - 8) { // 한 틱 급강하 (바닥을 뚫거나 가장자리에서 떨어짐) — 위치 기록
			log(String.format(Locale.ROOT, "t=%.2f 급강하 %.1f → %s", time, lastY, fmt(new float[]{hero.x, hero.y, hero.z})));
		}
		if (!Float.isNaN(lastY) && hero.y < lastY - 0.3f) { // 낙하: 시작 지점과 30 넘게 떨어졌을 때 한 번 기록
			if (fallStart == null) {
				fallStart = new float[]{lastX, lastY, lastZ};
			} else if (fallStart[1] - hero.y > 30 && !fallLogged) {
				fallLogged = true;
				log(String.format(Locale.ROOT, "t=%.2f 낙하 %s 에서 → %s", time, fmt(fallStart), fmt(new float[]{hero.x, hero.y, hero.z})));
			}
		} else {
			fallStart = null;
			fallLogged = false;
		}
		lastX = hero.x;
		lastY = hero.y;
		lastZ = hero.z;
		if ((int) (time / 5) != (int) ((time - dt) / 5)) {
			log(String.format(Locale.ROOT, "t=%.0f 위치 %s 오브 %d%s %s", time, fmt(new float[]{hero.x, hero.y, hero.z}), redOrbs, mp >= 0 ? String.format(Locale.ROOT, " MP %.0f", mp) : "",
					enemy != null ? "적 " + fmt(new float[]{enemy.x, enemy.y, enemy.z}) : atGoal ? "목표 대기 " + (int) goalTime : "이동"));
		}
		if (time > timeout && !done) {
			finish("TIMEOUT", hero);
		}
		if (enemy != null && !(ROUTE_NO_FIGHT && routing()) && (bossActive || enemy.distance(hero) < FIGHT && !giveUp(enemy, hero, dt))) { // 보스전은 포기하지 않음
			if (bossActive && unstick && bossStuck(hero, enemy, dt)) {
				// 보스전에서 대상에 못 다가간 채 갇힘 (닻이 떨어진 상자·머리 사이 틈, ATHN01E) — 대상 쪽 같은 높이 점으로 순간이동 (기록)
				float dx = hero.x - enemy.x, dz = hero.z - enemy.z, l = Math.max(1f, (float) Math.hypot(dx, dz));
				float[] p = {enemy.x + dx / l * 150f, hero.y + 10f, enemy.z + dz / l * 150f};
				log(String.format(Locale.ROOT, "t=%.2f 보스전 갇힘 → 순간이동 %s", time, fmt(p)));
				teleports++;
				pendingTeleport = p;
				return bot.press(null);
			}
			bot.grabTarget = grabTarget;
			bot.stoneTarget = stoneTarget;
			bot.gaze = gaze;
			bot.selectGaze = selectGaze;
			bot.equipArtemis = artemis;
			bot.steer(hero, enemy, camYaw, dt);
			return bot;
		}
		if (routeIdx < route.size() && time >= ROUTE_AFTER) {
			return routeStep(hero, camYaw, dt);
		}
		if (cur == null) {
			cur = pick(hero);
			legTime = dwell = 0;
			teleportedToCur = false;
		}
		if (cur == null) {
			bot.steer(hero, null, camYaw, dt);
			return bot;
		}
		float d = (float) Math.hypot(cur[0] - hero.x, cur[2] - hero.z);
		legTime += dt;
		if (d < ARRIVE && Math.abs(cur[1] - hero.y) < 64 || atGoal) {
			dwell += dt;
			if (atGoal) {
				goalTime += dt;
				r1Clock += dt;
				float phase = goalTime % GOAL_CYCLE;
				if (phase >= GOAL_STAY && lastVisited != null) {
					// 주기마다 물러남 / 목표 너머 (마지막 방문 점 반대쪽) / 물러남 / 목표 너머 (같은 쪽) — LoadCheck 는 경계라 다음 레벨 바닥이
					// 더 안쪽에 있다 (ATHN03AA → ATHN03A). 어느 쪽이 안쪽인지 몰라 두 쪽 다
					int k = (int) (goalTime / GOAL_CYCLE) % 4;
					bot.walkTo(hero, k == 1 ? beyond(cur, lastVisited, 1) : k == 3 ? beyond(cur, lastVisited, -1) : lastVisited, camYaw);
					return bot;
				}
				if (d > ARRIVE || Math.abs(cur[1] - hero.y) > 64) { // 목표 쪽으로 걷다가 못 가면 순간이동
					if (phase > WALK_LIMIT && !teleportedThisCycle) {
						teleportedThisCycle = true;
						teleport(hero, cur);
					} else {
						bot.walkTo(hero, cur, camYaw);
						return bot;
					}
				}
				if (phase < dt * 2) {
					teleportedThisCycle = false;
				}
				if (goalTime > GOAL_CYCLE * 2 && !revisited && goal != null) {
					// 전환이 안 일어나면 목표 근처 볼륨을 먼 것부터 다시 — 마지막으로 밟은 LoadWad/LoadForWarp 가 슬롯 레벨을 정한다
					// (먼저 돈 순서가 가까운 순이라 되돌아가는 레벨이 마지막에 올라와 있으면 Warp 가 다음 레벨로 가지 않는다, ATRET01A)
					revisited = revisit = true;
					all.stream().filter(p -> Math.hypot(p[0] - goal[0], p[2] - goal[2]) < REVISIT)
							.sorted((a, b) -> Float.compare(dist2(b, goal), dist2(a, goal))).forEach(todo::add);
					log("목표 근처 볼륨 다시 방문 " + todo.size());
					atGoal = false;
					cur = null;
					bot.press(null);
					return bot;
				}
				if (goalTime > GOAL_WAIT && !done) {
					finish("STUCK_AT_GOAL", hero);
				}
				// R1 (상자·CSM 누르고 있기), R2 연타 (사용 버튼 — 들어 올리는 문·해치 TAndF: 잡고 연타해야 열림, ATHN01A HydraHatchDoor),
				// × (점프 — 출구 센서가 발 높이보다 위에 있는 곳, ATHN01F WarpToAthn2A 영역 1 은 계단 위 바닥 +28 부터)
				float rc = r1Clock % 3f;
				if (face != null && rc >= 0.15f && rc < 0.25f) {
					bot.walkTo(hero, face, camYaw); // 손잡이 쪽으로 돌아서기 (잡기 판정은 손잡이를 봐야, 0x1C3918)
					return bot;
				}
				PadButton b = rc < 0.1f ? PadButton.R1 : rc >= 2.6f && rc < 2.7f ? PadButton.CROSS
						: grabCue ? (rc % 1f < 0.1f ? PadButton.CIRCLE : null)
						: (int) (rc / 0.075f) % 2 == 0 ? PadButton.USE : null;
				bot.press(b);
				return bot;
			}
			if (dwell > DWELL) {
				visited++;
				lastVisited = cur;
				cur = null;
			}
			bot.press(dwell > 0.4f && dwell - dt <= 0.4f ? PadButton.CROSS : null); // 볼륨 안에서 한 번 점프 (발보다 높은 센서 영역)
			return bot;
		}
		if (legTime > WALK_LIMIT) {
			teleport(hero, cur);
			return bot.press(null);
		}
		bot.walkTo(hero, cur, camYaw);
		return bot;
	}

	/**
	 * -Dgow.auto.route: 싸움이 없을 때 차례로 하는 경로 단계 (';' 구분, 원작에 없음 — 퍼즐 장치 검증용, 43 문서 §14).
	 * <pre>
	 *   x,y,z[,반경]       걸어가기 (반경 기본 {@link #ARRIVE} 안), {@link #ROUTE_WALK} 초 안에 못 가면 순간이동 (기록)
	 *   x,y,z!             순간이동
	 *   use:fx,fz          (fx, fz) 쪽으로 몸을 돌리고 R2 한 번 (레버·크랭크·버튼 안내 1024)
	 *   hold:초:fx,fz      R2 를 누른 채 (fx, fz) 쪽으로 스틱 (크랭크 돌리기)
	 *   push:초:fx,fz      버튼 없이 (fx, fz) 쪽으로 스틱 (사다리·벽 쪽으로 걸어 들어가기)
	 *   up:초              카메라와 무관하게 스틱 위 (사다리 오르기)
	 *   stick:초:x,y[:버튼] 카메라와 무관한 스틱 값 (벽에서 뒤로 점프 = 0,-1:CROSS), 버튼은 단계 첫 프레임에 한 번
	 *   mash:초            R2 연타 (들어 올리는 문 TAndF)
	 *   crank:초:fx,fz     손잡이 쪽으로 돌아 R2 를 누른 채 스틱을 영웅 정면으로 (손잡이를 밀며 둘레를 도는 Type 1 크랭크, PAND00B 문 크랭크)
	 *   pull:초:fx,fz      (fx, fz) 쪽으로 돌아 R2 를 누른 채 스틱을 영웅 정면 반대로 (밀기 상자 끌어내기)
	 *   jump:초:fx,fz      첫 프레임 × + (fx, fz) 쪽으로 스틱 (벽에 뛰어 붙기)
	 *   post:번호[:인자0]  스크립트 전역 이벤트 (보낸 쪽 = 영웅)
	 *   cam:이름           CMV 질의 끄고 카메라 요청 (Internal 0x9 false + 0x8), "cam:" = 질의 다시 켬
	 *   wait:초            입력 없음
	 *   btn:이름           버튼 한 번 (PadButton 이름)
	 *   die:               영웅 HP 0 (체크포인트 재시작 검증)
	 *   break:소유GO       부서지는 물체를 부숨 (지금 레벨, 없으면 다른 슬롯 레벨 것)
	 *   kill:AI1           그 템플릿의 살아 있는 적을 모두 HP 0 + EV_DEATH (빈 값 = 가장 가까운 적) — 봇이 못 이기는 싸움 건너뛰기
	 * </pre>
	 */
	private final List<String> route = parseRoute(System.getProperty("gow.auto.route"));
	/** gow.auto.route.after=초: 그 전에는 경로 대신 보통 자동 플레이 (보스전·볼륨 방문으로 앞 구간을 진행, 예: 메두사 여왕·미노타우로스 방 → 126 초 문, 43 §14.6). */
	static final float ROUTE_AFTER = Float.parseFloat(System.getProperty("gow.auto.route.after", "0"));
	/**
	 * gow.auto.route.nofight: 경로 단계가 남아 있는 동안 적과 싸우지 않는다 — 장치 연출 대조용 (ATHN05C 레버 병사 aoXR5 는 다리 건너편 적이라
	 * 봇이 그쪽으로만 붙어 경로 R2 를 못 눌렀다, 43 §20) [엔진 전용].
	 */
	static final boolean ROUTE_NO_FIGHT = System.getProperty("gow.auto.route.nofight") != null;
	private int routeIdx;
	private float routeTime;

	static List<String> parseRoute(String s) {
		List<String> out = new ArrayList<>();
		if (s != null) {
			for (String p : s.split(";")) {
				if (!p.isBlank()) {
					out.add(p.trim());
				}
			}
		}
		return out;
	}

	/** 걸어가기 단계의 순간이동까지 초. */
	static final float ROUTE_WALK = 6f;

	private BotInput routeStep(Vector3f hero, float camYaw, float dt) {
		String s = route.get(routeIdx);
		if (routeTime == 0) {
			log(String.format(Locale.ROOT, "t=%.2f 경로 %d/%d %s (위치 %s)", time, routeIdx + 1, route.size(), s, fmt(new float[]{hero.x, hero.y, hero.z})));
		}
		routeTime += dt;
		int c = s.indexOf(':');
		String op = c > 0 ? s.substring(0, c) : "";
		String[] a = c > 0 ? s.substring(c + 1).split(":") : new String[0];
		switch (op) {
			case "use" -> {
				float[] f = xz(a[0], hero);
				if (routeTime < 0.25f) {
					return bot.hold(null, hero, f, null, camYaw, 0.2f); // 몸만 돌리기 (스틱 작게 — 손잡이 앞을 벗어나지 않게)
				}
				if (routeTime < 0.35f && !usePressed) {
					// 한 번만 (세이브 질문·메뉴로 게임이 멈춘 사이 이 단계가 이어지면 R2 를 다시 눌러 세이브 질문이 또 열렸다)
					usePressed = true;
					return bot.press(PadButton.USE);
				}
				return routeNext(routeTime > 0.6f, bot.press(null));
			}
			case "hold", "push" -> {
				float secs = Float.parseFloat(a[0]);
				float[] f = a.length > 1 ? xz(a[1], hero) : null;
				return routeNext(routeTime > secs, bot.hold(op.equals("hold") ? PadButton.USE : null, hero, f, null, camYaw));
			}
			case "up" -> {
				return routeNext(routeTime > Float.parseFloat(a[0]), bot.hold(null, hero, null, new float[]{0, 1}, camYaw));
			}
			case "stick" -> {
				// stick:초:x,y[:버튼] — 카메라와 무관한 스틱 (벽 오르기 위 0,1 · 뒤로 점프 0,-1:CROSS), 버튼은 단계 시작 프레임에 한 번
				String[] q = a[1].split(",");
				PadButton btn = a.length > 2 && routeTime < 0.05f ? PadButton.valueOf(a[2].toUpperCase(Locale.ROOT)) : null;
				return routeNext(routeTime > Float.parseFloat(a[0]),
						bot.hold(btn, hero, null, new float[]{Float.parseFloat(q[0]), Float.parseFloat(q[1])}, camYaw));
			}
			case "mash" -> {
				return routeNext(routeTime > Float.parseFloat(a[0]), bot.press((int) (routeTime / 0.075f) % 2 == 0 ? PadButton.USE : null));
			}
			case "crank" -> {
				// crank:초:fx,fz — 손잡이 쪽으로 돌아서 R2 를 누르고 (0.25 초) 누른 채 스틱을 영웅 정면으로 (손잡이를 밀며 크랭크 둘레를 도는 Type 1
				// 크랭크: 고정점을 향한 스틱은 손잡이 축과 직각이 되어 입력이 0 이다 — 원작 입력 0x2117E0 = 이동 방향 · 손잡이 −Z)
				if (routeTime < 0.25f) {
					return bot.hold(null, hero, xz(a[1], hero), null, camYaw, 0.2f);
				}
				if (routeTime < 0.5f) {
					return bot.hold(PadButton.USE, hero, null, null, camYaw);
				}
				float[] f = {hero.x - (float) Math.sin(heroYaw) * 100f, hero.y, hero.z - (float) Math.cos(heroYaw) * 100f};
				return routeNext(routeTime > Float.parseFloat(a[0]), bot.hold(PadButton.USE, hero, f, null, camYaw));
			}
			case "pull" -> {
				// pull:초:fx,fz — crank 와 같이 (fx, fz) 쪽으로 돌아 R2 를 누르고, 누른 채 스틱을 영웅 정면의 반대로 (밀기 상자 끌어내기, DEST02 블록 방) [엔진 전용 QA]
				if (routeTime < 0.25f) {
					return bot.hold(null, hero, xz(a[1], hero), null, camYaw, 0.2f);
				}
				if (routeTime < 0.5f) {
					return bot.hold(PadButton.USE, hero, null, null, camYaw);
				}
				float[] f = {hero.x + (float) Math.sin(heroYaw) * 100f, hero.y, hero.z + (float) Math.cos(heroYaw) * 100f};
				return routeNext(routeTime > Float.parseFloat(a[0]), bot.hold(PadButton.USE, hero, f, null, camYaw));
			}
			case "post" -> {
				// post:번호[:인자0] — 스크립트 전역 이벤트 (보낸 쪽 = 영웅; 예 post:501:a06Mp03 = 동영상 끝) [엔진 전용: 앞 퍼즐을 건너뛸 때]
				if (scriptPost != null) {
					scriptPost.accept(Integer.parseInt(a[0]), a.length > 1 ? (a[1].matches("-?\\d+") ? (Object) Integer.parseInt(a[1]) : a[1]) : 0);
				}
				return routeNext(true, bot.press(null));
			}
			case "cam" -> {
				// cam:이름 — 스크립트 SwitchCamera 와 같이 카메라 요청 [엔진 전용]
				if (camera != null) {
					camera.accept(a[0]);
				}
				return routeNext(true, bot.press(null));
			}
			case "jump" -> {
				// jump:초:fx,fz — 첫 프레임 × 와 함께 (fx, fz) 쪽으로 스틱 (덩굴 벽에 뛰어 붙기)
				float[] f = xz(a[1], hero);
				return routeNext(routeTime > Float.parseFloat(a[0]), bot.hold(routeTime < 0.05f ? PadButton.CROSS : null, hero, f, null, camYaw));
			}
			case "wait" -> {
				return routeNext(routeTime > Float.parseFloat(a[0]), bot.press(null));
			}
			case "die" -> {
				// die: — 영웅 HP 0 (사망 → 체크포인트 재시작 검증, 43 §26) [엔진 전용]
				pendingDie = true;
				return routeNext(true, bot.press(null));
			}
			case "break" -> {
				// break:소유GO — 부서지는 물체를 부순다 (앞 구간의 마법 파괴 대신, 예 ATHN05C 돌문 gobreakdoorstone Hit Points 99) [엔진 전용]
				pendingBreak = a[0];
				return routeNext(true, bot.press(null));
			}
			case "kill" -> {
				// kill:AI1 — 그 템플릿의 살아 있는 적을 모두 HP 0 + EV_DEATH (봇이 못 이기는 싸움을 건너뛰고 뒤 스크립트를 볼 때, 예 DEST01 세이렌 122) [엔진 전용]
				pendingKill = a.length > 0 && !a[0].isBlank() ? Integer.parseInt(a[0].trim()) : -1;
				return routeNext(true, bot.press(null));
			}
			case "btn" -> {
				if (routeTime < 0.1f) {
					return bot.press(PadButton.valueOf(a[0].toUpperCase(Locale.ROOT)));
				}
				return routeNext(routeTime > 0.3f, bot.press(null));
			}
			default -> {
				boolean tp = s.endsWith("!");
				String[] q = (tp ? s.substring(0, s.length() - 1) : s).split(",");
				float[] p = {Float.parseFloat(q[0]), Float.parseFloat(q[1]), Float.parseFloat(q[2])};
				if (tp) {
					teleports++;
					pendingTeleport = p;
					log(String.format(Locale.ROOT, "t=%.2f 경로 순간이동 %s", time, fmt(p)));
					return routeNext(true, bot.press(null));
				}
				float d = (float) Math.hypot(p[0] - hero.x, p[2] - hero.z);
				float arrive = q.length > 3 ? Float.parseFloat(q[3]) : ARRIVE; // x,y,z,반경
				if (d < arrive && Math.abs(p[1] - hero.y) < 64) {
					return routeNext(true, bot.press(null));
				}
				if (routeTime > ROUTE_WALK) {
					teleports++;
					pendingTeleport = p;
					log(String.format(Locale.ROOT, "t=%.2f 경로 걸어서 못 감 (거리 %.0f, 높이 차 %.0f) → 순간이동 %s", time, d, p[1] - hero.y, fmt(p)));
					return routeNext(true, bot.press(null));
				}
				bot.walkTo(hero, p, camYaw);
				return bot;
			}
		}
	}

	/** 경로 단계 post / cam 의 대상 (GameScene 이 넣음, 없으면 무시). */
	public java.util.function.BiConsumer<Integer, Object> scriptPost;
	public java.util.function.Consumer<String> camera;

	/** "fx,fz" 또는 "fx,fy,fz" → 월드 점 (y = 영웅 높이). */
	private static float[] xz(String s, Vector3f hero) {
		String[] q = s.split(",");
		return q.length >= 3 ? new float[]{Float.parseFloat(q[0]), Float.parseFloat(q[1]), Float.parseFloat(q[2])}
				: new float[]{Float.parseFloat(q[0]), hero.y, Float.parseFloat(q[1])};
	}

	/** 이 "use" 단계에서 R2 를 이미 눌렀는지. */
	private boolean usePressed;

	private BotInput routeNext(boolean done, BotInput in) {
		if (done) {
			routeIdx++;
			routeTime = 0;
			usePressed = false;
			if (routeIdx == route.size()) {
				log(String.format(Locale.ROOT, "t=%.2f 경로 끝", time));
			}
		}
		return in;
	}

	/** 경로 단계가 남아 있는지 (GameScene: 경로 중에는 다른 레벨 전환을 막지 않는다). */
	public boolean routing() {
		return routeIdx < route.size();
	}

	private float[] objective;
	private float objectiveTime;

	/**
	 * 보스전이 정한 목적지 (BossFight.autoObjective — 쓰러진 아기 히드라의 닻 볼륨): 걸어가서 가까우면 이단 점프, {@link #WALK_LIMIT} × 2 초 안에
	 * 못 들어가면 순간이동 (기록). 볼륨이 공중 (갑판 위 265) 이라 봇은 상자 오르기를 못 한다.
	 */
	public BotInput reach(Vector3f hero, float[] p, float camYaw, float dt) {
		time += dt;
		if (objective == null || objective[0] != p[0] || objective[2] != p[2]) {
			objective = p;
			objectiveTime = 0;
			log(String.format(Locale.ROOT, "t=%.2f 보스 목적지 %s", time, fmt(p)));
		}
		objectiveTime += dt;
		float d = (float) Math.hypot(p[0] - hero.x, p[2] - hero.z);
		if (objectiveTime > WALK_LIMIT * 2 && objectiveTime - dt <= WALK_LIMIT * 2) {
			log(String.format(Locale.ROOT, "t=%.2f 보스 목적지 순간이동 (거리 %.0f, 높이 차 %.0f)", time, d, p[1] - hero.y));
			teleports++;
			pendingTeleport = new float[]{p[0], p[1], p[2]};
			return bot.press(null);
		}
		if (d < ARRIVE) {
			float c = objectiveTime % 0.6f;
			return bot.press(c < 0.05f || c >= 0.25f && c < 0.3f ? PadButton.CROSS : null);
		}
		bot.walkTo(hero, p, camYaw);
		return bot;
	}

	/** GameScene 이 매 틱: 보스전 쫄 (보스 본체 아님) 과 싸우는 중 — 갇힘 순간이동 허용 (본체 히드라는 바다 위라 순간이동 점이 없다). */
	public boolean unstick;
	private final Vector3f stuckAt = new Vector3f(Float.NaN, 0, 0);
	private float stuckTime;

	/** 보스전: {@link #STUCK_SECS} 초 동안 {@link #STUCK_MOVE} 도 못 움직였고 대상이 {@link #REACH} × 2 보다 멀면 참 (한 번 참이면 다시 셈). */
	private boolean bossStuck(Vector3f hero, Vector3f enemy, float dt) {
		if (Float.isNaN(stuckAt.x) || stuckAt.distance(hero) > STUCK_MOVE || (float) Math.hypot(enemy.x - hero.x, enemy.z - hero.z) < REACH * 2) {
			stuckAt.set(hero);
			stuckTime = 0;
			return false;
		}
		stuckTime += dt;
		if (stuckTime > STUCK_SECS) {
			stuckAt.x = Float.NaN;
			return true;
		}
		return false;
	}

	static final float STUCK_SECS = 8f, STUCK_MOVE = 24f;

	/** 적별 싸운 시간 / 가장 가까이 간 거리 (적 위치 벡터의 동일성으로 구분). */
	private final java.util.Map<Vector3f, float[]> fights = new java.util.IdentityHashMap<>();

	/**
	 * 못 이기는 적은 포기하고 볼륨 방문으로 넘어간다: {@link #REACH_LIMIT} 초 동안 {@link #REACH} 안에 못 들어감 (높이 차·벽 너머),
	 * 또는 {@link #FIGHT_LIMIT} 초 넘게 싸움. 기록은 한 번.
	 */
	private boolean giveUp(Vector3f enemy, Vector3f hero, float dt) {
		float[] f = fights.computeIfAbsent(enemy, k -> new float[]{0, Float.MAX_VALUE, 0});
		if (f[2] != 0) {
			return true;
		}
		f[0] += dt;
		f[1] = Math.min(f[1], enemy.distance(hero));
		if (f[0] > FIGHT_LIMIT || f[0] > REACH_LIMIT && f[1] > REACH) {
			f[2] = 1;
			log(String.format(Locale.ROOT, "t=%.0f 적 포기 %s (%.0f 초, 최소 거리 %.0f)", time, fmt(new float[]{enemy.x, enemy.y, enemy.z}), f[0], f[1]));
			return true;
		}
		return false;
	}

	static final float FIGHT_LIMIT = 90f, REACH_LIMIT = 20f, REACH = 80f;

	/** 목표에서 from 반대쪽 (sign −1 이면 같은 쪽) 으로 {@link #BEYOND} 떨어진 점 (수평). */
	private static float[] beyond(float[] goal, float[] from, int sign) {
		float dx = goal[0] - from[0], dz = goal[2] - from[2], l = (float) Math.hypot(dx, dz) / sign;
		return Math.abs(l) < 1 ? goal : new float[]{goal[0] + dx / l * BEYOND, goal[1], goal[2] + dz / l * BEYOND};
	}

	static final float BEYOND = 400f;

	private static float dist2(float[] a, float[] b) {
		return (a[0] - b[0]) * (a[0] - b[0]) + (a[2] - b[2]) * (a[2] - b[2]);
	}

	static final float REVISIT = 1000f;

	private float[] pick(Vector3f hero) {
		if (revisit && !todo.isEmpty()) {
			return todo.remove(0);
		}
		if (todo.isEmpty()) {
			if (goal != null && !atGoal) {
				atGoal = true;
				log("목표로 (방문 " + visited + ", 순간이동 " + teleports + ")");
				return goal;
			}
			return atGoal ? goal : null;
		}
		int best = 0;
		float bd = Float.MAX_VALUE;
		for (int i = 0; i < todo.size(); i++) {
			float[] p = todo.get(i);
			float d = (p[0] - hero.x) * (p[0] - hero.x) + (p[2] - hero.z) * (p[2] - hero.z) + 4 * (p[1] - hero.y) * (p[1] - hero.y);
			if (d < bd) {
				bd = d;
				best = i;
			}
		}
		return todo.remove(best);
	}

	private void teleport(Vector3f hero, float[] p) {
		teleports++;
		teleportedToCur = true;
		pendingTeleport = new float[]{p[0], p[1], p[2]};
	}

	private float[] pendingTeleport;

	/** GameScene 이 적용할 순간이동 (없으면 null, 한 번만). */
	public float[] takeTeleport() {
		float[] t = pendingTeleport;
		pendingTeleport = null;
		return t;
	}

	/** 경로 단계 die: 이번 틱에 영웅을 죽인다 (한 번). */
	public boolean takeDie() {
		boolean d = pendingDie;
		pendingDie = false;
		return d;
	}

	private boolean pendingDie;
	private String pendingBreak;

	/** 경로 단계 break:소유GO — 이번 틱에 부술 물체 (한 번, 없으면 null). */
	public String takeBreak() {
		String b = pendingBreak;
		pendingBreak = null;
		return b;
	}

	private Integer pendingKill;

	/** 경로 단계 kill:AI1 — 이번 틱에 죽일 적 템플릿 (한 번, −1 = 가장 가까운 적, 없으면 null). */
	public Integer takeKill() {
		Integer k = pendingKill;
		pendingKill = null;
		return k;
	}
	/**
	 * gow.auto.deadmenu: 자동 플레이도 죽으면 사망 메뉴 ("YOU ARE DEAD" → 1 초 뒤 ○ = 기본 선택 "마지막 체크포인트에서 다시 시작") 를 거친다 — 영상 8210 화면
	 * 대조용 [엔진 전용]. 없으면 메뉴 없이 곧바로 체크포인트 재시작.
	 */
	public static final boolean DEAD_MENU = System.getProperty("gow.auto.deadmenu") != null;

	private float menuClock;

	/**
	 * 질문 메뉴 입력: 0.5 초마다 ○ (게임 틱이 멈춰 있으므로 steer 대신). HUD FLP 메뉴의 선택 버튼은 ○ (패드 비트 0x20, UiPad — 한국판 화면
	 * "○ = 선택") 라 예전의 × 로는 질문 (ATHN04A 열쇠 1014, 세이브 제단) 에 답하지 못하고 멈췄다 [엔진 전용].
	 */
	public BotInput menu(float dt) {
		return menu(dt, false);
	}

	/**
	 * yesNo = 메모리카드 예/아니오 화면 (기본 선택이 아니오 — MemoryCardFlow 15/16): 1 초 뒤 ↑ 로 "예" 를 고르고 ○.
	 */
	public BotInput menu(float dt, boolean yesNo) {
		if (menuSeen < time - 0.1f || yesNo != menuYesNo) {
			menuClock = 0; // 새 메뉴·카드: 1 초 읽고 (증거 화면이 남게) 누른다
		}
		menuYesNo = yesNo;
		menuSeen = time;
		menuClock += dt;
		if (yesNo && menuClock > 1f && menuClock < 1.05f) {
			return bot.press(PadButton.UP);
		}
		float t = yesNo ? menuClock - 0.3f : menuClock;
		return bot.press(t > 1f && t % 0.5f < 0.05f ? PadButton.CIRCLE : null);
	}

	private boolean menuYesNo;

	/** 마지막으로 메뉴 입력을 준 자동 플레이 시각. */
	private float menuSeen = -1;

	public void died(Vector3f at) {
		deaths++;
		log("사망 #" + deaths + " at " + fmt(new float[]{at.x, at.y, at.z}));
		if (cur != null && !atGoal) {
			if (teleportedToCur) { // 순간이동한 볼륨 점이 죽는 바닥 (HDES01D gofallanimzone) — 다시 가지 않는다
				log("볼륨 포기 " + fmt(cur));
				cur = null;
			} else {
				legTime = WALK_LIMIT + 1; // 걸어가다 떨어짐 (뛰어넘을 틈, ATHN01D 부두) — 다음엔 바로 순간이동
			}
		}
		pendingTeleport = null;
		if (atGoal && ++goalDeaths >= 3 && !done) {
			finish("DEATH_AT_GOAL", at);
		}
	}

	private int goalDeaths;
	/** 지금 볼륨 점에 순간이동으로 갔는지. */
	private boolean teleportedToCur;

	/** 레벨 전환 요청: next 면 성공으로 끝내고 true, 아니면 막는다 (false). */
	public boolean allow(String target, Vector3f hero) {
		if (target.equalsIgnoreCase(next)) {
			finish("OK", hero);
			return true;
		}
		if (vetoed.add(target.toUpperCase(Locale.ROOT))) {
			log("다른 레벨 전환 막음: " + target);
		}
		vetoes++;
		return false;
	}

	public boolean done() {
		return done;
	}

	private void finish(String result, Vector3f hero) {
		done = true;
		String line = String.format(Locale.ROOT, "%s\t%s\t%s\t%.0fs\t방문 %d/%d\t순간이동 %d\t사망 %d\t막은 전환 %s\t마지막 %s\t오브 %d%n", level, next, result, time,
				visited, visited + todo.size(), teleports, deaths, vetoed, fmt(new float[]{hero.x, hero.y, hero.z}), redOrbs);
		System.out.print("AUTO 결과 " + line);
		String f = System.getProperty("gow.auto.log");
		if (f != null) {
			try {
				Files.writeString(Path.of(f), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException e) {
				System.err.println("AUTO 로그 실패: " + e);
			}
		}
	}

	private static void log(String s) {
		System.out.println("AUTO " + s);
	}

	private static String fmt(float[] p) {
		return String.format(Locale.ROOT, "(%.0f, %.0f, %.0f)", p[0], p[1], p[2]);
	}
}
