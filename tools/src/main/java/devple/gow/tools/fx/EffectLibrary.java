package devple.gow.tools.fx;

import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.audio.SoundEmitter;
import devple.gow.tools.level.GoInstance;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 이펙트 GO 해석 (40 문서). 튜닝 값의 이펙트 이름(예 {@code "goEyeBeam"}, {@code "goSklBlood"})은 GO 인스턴스
 * (CXT 0x00020001, 소문자 {@code goeyebeam}) 이름이고, 인스턴스 +0x04 가 오브젝트(CXT 0x00040001, {@code eyebeam})를 가리킨다.
 * 오브젝트 그룹의 참조가 이펙트 구성 요소다:
 * <pre>
 *   ANM_x  오브젝트 애니 (관절 회전 = 메시 변형, 타입 10 f32 채널 = FXC 파라미터 곡선)
 *   MDL_x  메시 (빔·충격파·번개 가지)        FXC_ 이미터(1..10) + PTC_ 모양 / FXC_ 필드(0xC)        SEM_ 사운드 이미터
 * </pre>
 * 수명 [확실: 0x228210 / 0x2282D0]: 1회성 이펙트 객체는 GO 애니의 재생 핸들 전부에 완료 콜백을 걸고 마지막 하나가 끝나면 스스로 지운다
 * → 애니 act 가 반복(flags bit0)이 아니면 수명 = act 길이, 반복이면 소유자가 지울 때까지.
 */
public final class EffectLibrary {

	/**
	 * 이미터 하나.
	 *
	 * @param fxc     FXC_ 이미터
	 * @param ptc     PTC_ 모양 (+0x54 모양 이름으로 연결)
	 * @param channel 노드 헤더 +0x0A s16 = ANM 타입 10 채널 묶음 번호 (−1 = 없음 → FXC 상수 생성률)
	 */
	public record EmitterDef(FxResources.Emitter fxc, FxResources.Shape ptc, int channel) {
	}

	/**
	 * 이펙트 GO 정의.
	 *
	 * @param go       인스턴스 이름 (소문자)
	 * @param object   오브젝트 이름
	 * @param wad      정의가 들어 있는 WAD
	 * @param scale    인스턴스 균일 스케일 (goboltchargeup 0.25 등)
	 * @param model    MDL_ 이름 (없으면 null)
	 * @param skeleton 오브젝트 스켈레톤 (관절 / 바인드)
	 * @param anim     ANM 첫 act (없으면 null)
	 * @param emitters FXC 이미터
	 * @param fields   FXC 필드 (중력 등)
	 * @param sound    SEM_ 사운드 이미터 이름 (없으면 null)
	 * @param acts     ANM 첫 그룹의 act 전부 (mewh "On"/"off" 처럼 이름으로 고르는 오브젝트, 41 문서 파괴 애니)
	 * @param sem      SEM_ 이미터 데이터 (같은 이름이 여럿이면 오브젝트 그룹 바로 앞의 정의 = WAD 스트리밍 순서, 없으면 null)
	 */
	public record EffectDef(String go, String object, WadFile wad, float scale, String model, Skeleton skeleton, Animation anim,
							List<EmitterDef> emitters, List<FxResources.Field> fields, String sound, List<Animation> acts,
							SoundEmitter sem) {

		/** 이름으로 act 찾기 (대소문자 무시, 없으면 null). */
		public Animation act(String name) {
			for (Animation a : acts) {
				if (a.name.equalsIgnoreCase(name)) {
					return a;
				}
			}
			return null;
		}

		/** 1회성 = 애니가 있고 반복이 아님 (0x228210 이 완료 콜백 수를 센다). */
		public boolean oneShot() {
			// 길이 0 인 정지 act (dedred 의 단일 프레임) 는 완료 콜백이 의미 없음 → 지속으로 본다 [추정]
			return anim != null && !anim.looping() && anim.duration > 0;
		}

		/**
		 * 수명 (초). 1회성 = act 길이, 반복/애니 없음 = 무한 (소유자가 끈다).
		 * 애니 없는 이펙트를 1회로 쓸 때의 대체 수명은 {@link #particleLife()}.
		 */
		public float lifetime() {
			return oneShot() ? anim.duration : Float.POSITIVE_INFINITY;
		}

		/** 가장 긴 PTC 수명 (애니 없는 1회 재생의 대체 수명, [근사]). */
		public float particleLife() {
			float m = 0;
			for (EmitterDef e : emitters) {
				m = Math.max(m, e.ptc().lifespan());
			}
			return m;
		}

		/**
		 * 시간 t 의 이미터 P 블록 값: 채널이 있으면 ANM 타입 10 곡선, 없으면 FXC 값.
		 *
		 * @param stream 3 퍼짐, 4 속력, 5 속력 랜덤, 8 초당 생성 수
		 */
		public float param(EmitterDef e, int stream, float t, float def) {
			return param(anim, e, stream, t, def);
		}

		/** {@link #param(EmitterDef, int, float, float)} 를 다른 act 로 (mewh "On"). */
		public float param(Animation a, EmitterDef e, int stream, float t, float def) {
			if (a == null || e.channel() < 0) {
				return def;
			}
			return a.channel(e.channel(), stream, t, def);
		}

		/**
		 * 이미터가 시간 t 에 내는 초당 생성 수. 채널 이펙트는 ANM 곡선(스트림 8), 아니면 FXC +0x20.
		 * 1회성 이펙트는 act 끝 이후 0.
		 */
		public float rate(EmitterDef e, float t) {
			return rate(anim, e, t);
		}

		/** 다른 act 의 생성률 (반복 act 는 t 를 act 길이로 감는다). */
		public float rate(Animation a, EmitterDef e, float t) {
			boolean once = a != null && !a.looping() && a.duration > 0;
			if (once && t > a.duration) {
				return 0;
			}
			if (a != null && a.looping() && a.duration > 0) {
				t %= a.duration;
			}
			if (a != null && e.channel() >= 0 && e.channel() < a.channelGroups.size() && !a.channelGroups.get(e.channel()).isEmpty()) {
				return Math.max(0, a.channel(e.channel(), 8, t, 0));
			}
			if (once && e.channel() >= 0 && t > BURST) {
				return 0; // 링크 번호가 있는데 채널이 없는 1회성 (boltHit 의 emitter1 600/초) → 처음 0.1초만 [근사]
			}
			return Math.max(0, e.fxc().rate());
		}
	}

	/** 채널 없는 링크 이미터의 1회성 분출 시간 (초) [근사]. */
	public static final float BURST = 0.1f;

	private final Map<String, EffectDef> byName = new LinkedHashMap<>();

	/** WAD 의 모든 GO 인스턴스를 등록한다. 같은 이름은 나중에 더한 WAD 가 덮는다 (마법 단계 WAD 우선). */
	public void add(WadFile wad) {
		@SuppressWarnings("unchecked")
		List<Parsed> defs = (List<Parsed>) wad.takeDerived(EffectLibrary.class);
		for (Parsed d : defs != null ? defs : parse(wad)) {
			byName.put(d.key(), d.def());
		}
	}

	private record Parsed(String key, EffectDef def) {
	}

	/**
	 * {@link #add} 의 해석을 미리 (GL 없음 → 적재 스레드, 48 문서 §9). 같은 WadFile 의 다음 add 가 결과를 한 번 꺼내 쓴다 — 같은 코드로 만든 같은 값.
	 */
	public static void preparse(WadFile wad) {
		wad.putDerived(EffectLibrary.class, parse(wad)); // WAD 에 붙여 둔다 (WAD 와 함께 버려짐 — 전역 표 없음)
	}

	private static List<Parsed> parse(WadFile wad) {
		List<Parsed> out = new ArrayList<>();
		FxResources fx = new FxResources(wad);
		Map<String, Skeleton> objects = new HashMap<>();
		for (Skeleton s : Skeleton.loadAll(wad)) {
			objects.putIfAbsent(s.name, s);
		}
		Map<String, AnimationSet> anims = new HashMap<>();
		for (AnimationSet s : AnimationSet.loadAll(wad)) {
			anims.putIfAbsent(s.name, s);
		}
		// SEM_ 은 같은 이름("SEM_soundEmitter")이 여러 번 정의된다 → 오브젝트 그룹 앞의 마지막 정의를 쓴다 (WAD 순차 적재)
		Map<String, List<int[]>> semAt = new HashMap<>();
		Map<String, Integer> cxtAt = new HashMap<>();
		List<WadTag> tags = wad.tags();
		for (int i = 0; i < tags.size(); i++) {
			WadTag t = tags.get(i);
			if (!t.hasData()) {
				continue;
			}
			if (SoundEmitter.isEmitter(t.name(), t.data())) {
				semAt.computeIfAbsent(t.name(), k -> new ArrayList<>()).add(new int[]{i});
			} else if (t.magic() == Skeleton.MAGIC) {
				cxtAt.putIfAbsent(t.name(), i);
			}
		}
		for (WadTag t : wad.resources()) {
			if (!t.hasData() || t.magic() != GoInstance.MAGIC || t.size() < 0x40) {
				continue;
			}
			GoInstance gi = GoInstance.parse(t.name(), t.data());
			Skeleton sk = objects.get(gi.object());
			if (sk == null) {
				continue;
			}
			out.add(new Parsed(t.name().toLowerCase(Locale.ROOT), build(wad, fx, gi, sk, anims, semAt, cxtAt)));
		}
		return out;
	}

	private static EffectDef build(WadFile wad, FxResources fx, GoInstance gi, Skeleton sk, Map<String, AnimationSet> anims,
								   Map<String, List<int[]>> semAt, Map<String, Integer> cxtAt) {
		String model = null;
		String sound = null;
		Animation anim = null;
		List<Animation> acts = List.of();
		List<EmitterDef> em = new ArrayList<>();
		List<FxResources.Field> fields = new ArrayList<>();
		for (String r : Skeleton.groupRefs(wad, sk.name)) {
			if (r.startsWith("MDL_") && model == null) {
				model = r;
			} else if (r.startsWith("SEM_") && sound == null) {
				sound = r;
			} else if (r.startsWith("ANM_") && anim == null) {
				AnimationSet set = anims.get(r);
				if (set != null && !set.groups.isEmpty() && !set.groups.getFirst().acts.isEmpty()) {
					anim = set.groups.getFirst().acts.getFirst();
					acts = List.copyOf(set.groups.getFirst().acts);
				}
			} else if (r.startsWith("FXC_")) {
				FxResources.Emitter e = fx.emitters.get(r);
				if (e != null) {
					FxResources.Shape sh = fx.byShape.get(e.shape());
					if (sh != null) {
						em.add(new EmitterDef(e, sh, e.header()[1]));
					}
				} else if (fx.fields.containsKey(r)) {
					fields.add(fx.fields.get(r));
				}
			}
		}
		SoundEmitter sem = null;
		List<int[]> cands = sound != null ? semAt.get(sound) : null;
		if (cands != null) {
			int at = cxtAt.getOrDefault(sk.name, Integer.MAX_VALUE);
			int best = cands.getFirst()[0];
			for (int[] c : cands) {
				if (c[0] < at) {
					best = c[0];
				}
			}
			WadTag t = wad.tags().get(best);
			sem = SoundEmitter.parse(t.name(), t.data());
		}
		return new EffectDef(gi.name().toLowerCase(Locale.ROOT), gi.object(), wad, gi.scale() > 0 ? gi.scale() : 1f, model, sk, anim,
				List.copyOf(em), List.copyOf(fields), sound, acts, sem);
	}

	/** 이펙트 이름 (대소문자 무시, "go" 접두 생략 가능) → 정의. 없으면 null. */
	public EffectDef resolve(String name) {
		if (name == null || name.isEmpty()) {
			return null;
		}
		String k = name.toLowerCase(Locale.ROOT);
		EffectDef d = byName.get(k);
		return d != null ? d : byName.get("go" + k);
	}

	public int size() {
		return byName.size();
	}

	public java.util.Set<String> names() {
		return byName.keySet();
	}

	/**
	 * 필드 → 월드 가속 (월드/초²). 이펙트 필드는 크기·방향이 Z 를 수직축으로 쓴다
	 * (SKLgrav −7·(0,0,1), Fsgrave 9.8·(0,0,−1), dustgrav −2·(0,0,1): 모두 "아래") → (x, y, z) ↦ (x, z, −y)·16 [추정].
	 * 중력(3)·균일(6) 만, 나머지는 0.
	 */
	public static float[] fieldAccel(List<FxResources.Field> fields) {
		float[] a = new float[3];
		for (FxResources.Field f : fields) {
			if (f.kind() != 3 && f.kind() != 6) {
				continue;
			}
			float[] d = f.dir();
			float l = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
			if (l < 1e-6f) {
				continue;
			}
			float m = f.magnitude() * 16 / l;
			a[0] += d[0] * m;
			a[1] += d[2] * m;
			a[2] += -d[1] * m;
		}
		return a;
	}
}
