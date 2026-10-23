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
 *   <li>마지막으로 {@code gow.auto.goal} (주 경로 전환 지점) 에서 R1 을 누르며 기다린다</li>
 * </ol>
 * {@code gow.auto.next} 가 아닌 레벨로의 전환은 막고 기록한다. 결과 한 줄을 {@code gow.auto.log} 에 덧붙이고 창을 닫는다.
 */
public final class AutoPlay {

	public static final boolean ON = System.getProperty("gow.auto") != null;
	static final float FIGHT = 500f, WALK_LIMIT = 3f, DWELL = 1f, GOAL_WAIT = 90f, ARRIVE = 40f;
	/** 목표에서 기다리는 주기: 앞 6 초는 목표 위 (R1), 뒤 4 초는 마지막 방문 점으로 물러난다 — 목표 위에 서 있으면 보류되는 스크립트 (ATHN01A 해치). */
	static final float GOAL_CYCLE = 10f, GOAL_STAY = 6f;

	private final String level, next;
	private final float[] goal;
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
			if (p != null && p[3] == 0) {
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
			log(String.format(Locale.ROOT, "t=%.0f 위치 %s 오브 %d %s", time, fmt(new float[]{hero.x, hero.y, hero.z}), redOrbs,
					enemy != null ? "적 " + fmt(new float[]{enemy.x, enemy.y, enemy.z}) : atGoal ? "목표 대기 " + (int) goalTime : "이동"));
		}
		if (time > timeout && !done) {
			finish("TIMEOUT", hero);
		}
		if (enemy != null && (bossActive || enemy.distance(hero) < FIGHT && !giveUp(enemy, hero, dt))) { // 보스전은 포기하지 않음
			bot.steer(hero, enemy, camYaw, dt);
			return bot;
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
				// R1 (문·레버·상자) 과 × (점프 — 출구 센서가 발 높이보다 위에 있는 곳, ATHN01F WarpToAthn2A 영역 1 은 계단 위 바닥 +28 부터)
				float rc = r1Clock % 3f;
				bot.press(rc < 0.1f ? PadButton.R1 : rc >= 1.5f && rc < 1.6f ? PadButton.CROSS : null);
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

	private float menuClock;

	/** 질문 메뉴 입력: 0.5 초마다 × (게임 틱이 멈춰 있으므로 steer 대신). */
	public BotInput menu(float dt) {
		menuClock += dt;
		return bot.press(menuClock % 0.5f < 0.05f ? PadButton.CROSS : null);
	}

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
