package devple.gow.engine.verify;

import devple.gow.engine.verify.TraversalVerifier.Arrival;
import devple.gow.engine.verify.TraversalVerifier.Edge;
import devple.gow.engine.verify.TraversalVerifier.LevelResult;
import devple.gow.engine.verify.TraversalVerifier.Spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** {@link TraversalVerifier} 결과 → Markdown 보고서 (work/verify/report.md, 36 문서). */
public final class TraversalReport {

	private TraversalReport() {
	}

	public static String markdown(TraversalVerifier v) {
		StringBuilder o = new StringBuilder();
		Map<String, String> reach = v.reach(TraversalVerifier.START);
		List<String> path = v.path(TraversalVerifier.START, TraversalVerifier.END);
		o.append("# 전 레벨 순회 검증 보고서\n\n");
		o.append("생성: `TraversalVerifier` (36 문서). 레벨 ").append(v.results.size()).append("개.\n\n");
		o.append("## 요약\n\n");
		o.append("- ").append(TraversalVerifier.START).append(" → ").append(TraversalVerifier.END).append(": ")
				.append(path.isEmpty() ? "**도달 불가**" : "도달 (" + (path.size() - 1) + "번 전환)").append('\n');
		if (!path.isEmpty()) {
			o.append("  - 경로: ").append(String.join(" → ", path)).append('\n');
		}
		List<String> walkPath = pathOf(v.reach(TraversalVerifier.START, false), TraversalVerifier.END);
		o.append("- 걷기만(게임 이벤트 강제 없이): ").append(walkPath.isEmpty() ? "도달 불가" : "도달").append(", 도달 레벨 ")
				.append(v.reach(TraversalVerifier.START, false).size()).append('\n');
		o.append("- ").append(TraversalVerifier.START).append(" 에서 도달 가능한 레벨: ").append(reach.size()).append(" / ")
				.append(v.results.size()).append('\n');
		List<String> unreached = v.results.keySet().stream().filter(l -> !reach.containsKey(l)).toList();
		o.append("- 도달 불가 레벨: ").append(unreached.isEmpty() ? "없음" : String.join(", ", unreached)).append('\n');
		for (String u : unreached) {
			List<String> by = v.results.values().stream().filter(r -> r.loads.containsKey(u)).map(r -> r.level).toList();
			boolean incoming = v.results.values().stream().anyMatch(r -> r.edges.stream().anyMatch(e -> e.target().equals(u)));
			if (!by.isEmpty() && !incoming) {
				o.append("  - ").append(u).append(": ").append(String.join(", ", by)).append(" 이 LoadWad 만 함 (전환·바닥 이음 없음 → 보조 WAD 또는 미구현 이동)\n");
			}
		}
		List<String> bonus = v.path(TraversalVerifier.BONUS_START, TraversalVerifier.BONUS_END);
		o.append("- 보너스 '신들의 도전' ").append(TraversalVerifier.BONUS_START).append(" → ").append(TraversalVerifier.BONUS_END).append(": ")
				.append(bonus.isEmpty() ? "**도달 불가**" : String.join(" → ", bonus)).append('\n');
		long exc = v.results.values().stream().filter(r -> !r.exceptions.isEmpty()).count();
		long unb = v.results.values().stream().filter(r -> !r.unbalanced.isEmpty()).count();
		long noGround = v.results.values().stream().filter(r -> !r.spawnGround && r.spawnFloorDistance == Float.MAX_VALUE).count();
		o.append("- 예외 레벨 ").append(exc).append(", 스택 불균형 레벨 ").append(unb).append(", 시작 지점 바닥 없음(보정 불가) ").append(noGround).append('\n');
		o.append("- 누락 리소스 (배치 인스턴스의 MDL/ANM/MAT/TXR, RSRCS WAD): ").append(join(v.results.values().stream()
				.filter(r -> !r.missing.isEmpty()).map(r -> r.level + " " + r.missing.size()).toList())).append('\n');
		o.append("- 동영상 누락: ").append(join(v.results.values().stream().flatMap(r -> r.movies.entrySet().stream()
				.filter(e -> !e.getValue()).map(e -> r.level + ":" + e.getKey())).toList())).append('\n');
		o.append("- 메시지 누락: ").append(join(v.results.values().stream().flatMap(r -> r.messages.entrySet().stream()
				.filter(e -> !e.getValue()).map(e -> r.level + ":" + e.getKey())).toList())).append('\n');
		o.append("- 없는 WAD 요청: ").append(join(v.results.values().stream().flatMap(r -> r.badWads.stream().map(b -> r.level + ": " + b)).toList()))
				.append('\n');
		o.append("- 미해결 적 템플릿: ").append(join(v.results.values().stream().flatMap(r -> r.spawns.values().stream()
				.filter(s -> s.resolvedBy() == null).map(s -> r.level + ":" + s.templateId())).distinct().toList())).append('\n');
		o.append("- 모델 없는 템플릿 (사운드 뱅크 로더 등, 전투 개체 안 만듦): ").append(join(v.results.values().stream().flatMap(r -> r.spawns.values()
				.stream().filter(s -> s.resolvedBy() != null && s.model() == null && !"stand-in".equals(s.resolvedBy())).map(s -> r.level + ":" + s.templateId() + "(" + s.resolvedBy() + ")"))
				.distinct().toList())).append('\n');
		o.append("- 보스/특수 템플릿 (오브젝트 없음 → 대역 [근사]): ").append(join(v.results.values().stream().flatMap(r -> r.spawns.values()
				.stream().filter(s -> "stand-in".equals(s.resolvedBy())).map(s -> r.level + ":" + s.templateId() + " " + s.model()))
				.distinct().toList())).append('\n');
		o.append("- 레벨 자체 AI1 템플릿 → 레벨 오브젝트: ").append(join(v.results.values().stream().flatMap(r -> r.spawns.values().stream()
				.filter(s -> "level".equals(s.resolvedBy()) && s.model() != null).map(s -> r.level + ":" + s.templateId() + "→" + s.model())).distinct().toList()))
				.append('\n');
		o.append("- 도착 검사 실패: ").append(join(v.results.values().stream().flatMap(r -> r.arrivals.stream().filter(a -> !a.ok())
				.map(a -> a.from() + "→" + r.level + " " + a.kind() + " " + a.detail())).toList())).append("\n\n");

		o.append("## 주 경로 상세\n\n");
		o.append("| # | 전환 | 종류 | 강제 필요 | 계기 (볼륨/엔티티) |\n|---|---|---|---|---|\n");
		for (int i = 0; i + 1 < path.size(); i++) {
			String a = path.get(i), b = path.get(i + 1);
			Edge best = v.results.get(a).edges.stream().filter(e -> e.target().equals(b)).min(java.util.Comparator.comparing(Edge::forced))
					.orElse(null);
			if (best != null) {
				o.append("| ").append(i + 1).append(" | ").append(a).append(" → ").append(b).append(" | ").append(best.kind()).append(" | ")
						.append(best.forced() ? "예" : "").append(" | ").append(best.source() == null ? "" : best.source()).append(" |\n");
			}
		}
		long all = v.results.values().stream().mapToLong(r -> r.edges.size()).sum();
		o.append("\n전환 수: ").append(all);
		for (var k : devple.gow.engine.game.LevelLink.Kind.values()) {
			o.append(", ").append(k).append(" ").append(v.results.values().stream().flatMap(r -> r.edges.stream()).filter(e -> e.kind() == k).count());
		}
		o.append(" (강제 뒤 ").append(v.results.values().stream().flatMap(r -> r.edges.stream()).filter(Edge::forced).count()).append(")\n\n");
		o.append("## 레벨별\n\n");
		o.append("세이브 = 발동된 / 전체 savepoint Creator. 전환: `~` LoadCheck 이음, `≈` 이웃 바닥으로 걸어 들어감, `⇒` Warp, `→` Load. `*` = 게임 이벤트 강제 뒤에만 나온 전환. 도착 `**…**` = 검사 실패.\n\n");
		o.append("| 레벨 | 도달 | 시작 | 바닥 | 엔티티 | 볼륨(방문/공중) | 전환 | 도착 | 적 스폰 | 동영상 | 메시지 | 세이브 | CP | 오류 | 표지 GO | 누락 리소스 |\n");
		o.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
		for (LevelResult r : v.results.values()) {
			o.append("| ").append(r.level)
					.append(" | ").append(reach.containsKey(r.level) ? "O" : "–")
					.append(" | ").append(r.spawnSource).append(String.format(Locale.ROOT, " (%.0f, %.0f, %.0f)", r.spawn[0], r.spawn[1], r.spawn[2]))
					.append(" | ").append(r.spawnGround ? "O" : (r.spawnFloorDistance < Float.MAX_VALUE ? "보정 " + (int) r.spawnFloorDistance : "**X**"))
					.append(" | ").append(r.entities)
					.append(" | ").append(r.visited).append("/").append(r.volumes).append(r.airborne > 0 ? " (" + r.airborne + ")" : "")
					.append(" | ").append(r.edges.stream().map(TraversalReport::edge).collect(Collectors.joining(", ")))
					.append(" | ").append(r.arrivals.stream().map(TraversalReport::arrival).collect(Collectors.joining(", ")))
					.append(" | ").append(r.spawns.values().stream().map(TraversalReport::spawn).distinct().collect(Collectors.joining(", ")))
					.append(" | ").append(r.movies.entrySet().stream().map(e -> e.getKey() + (e.getValue() ? "" : " **없음**"))
							.collect(Collectors.joining(", ")))
					.append(" | ").append(r.messages.size()).append(r.messages.containsValue(false) ? " **누락**" : "")
					.append(" | ").append(r.savePoints.size()).append("/").append(r.savePointCreators)
					.append(" | ").append(r.checkpoints)
					.append(" | ").append(errors(r))
					.append(" | ").append(r.markerGos)
					.append(" | ").append(r.missing.isEmpty() ? "" : r.missing.size() + ": " + r.missing.stream().limit(4).collect(Collectors.joining(", "))
							+ (r.missing.size() > 4 ? " …" : ""))
					.append(" |\n");
		}
		o.append("\n## 상세 (요청 · Goto · 오류)\n\n");
		for (LevelResult r : v.results.values()) {
			List<String> lines = new ArrayList<>();
			if (!r.requests.isEmpty()) {
				lines.add("요청: " + String.join("; ", r.requests));
			}
			List<String> markers = r.loadChecks.entrySet().stream().filter(x -> r.edges.stream()
					.noneMatch(e -> e.target().equals(x.getKey()) && e.kind() == devple.gow.engine.game.LevelLink.Kind.SEAMLESS))
					.map(x -> {
						float d = TraversalVerifier.nearestFloor(v.floorCellsOf(x.getKey()), x.getValue().pos());
						return x.getKey() + (d < Float.MAX_VALUE ? " (대상 바닥 " + (int) d + ")" : "");
					}).toList();
			if (!markers.isEmpty()) {
				lines.add("LoadCheck 표지만 (그 자리에 대상 바닥 없음 → 현재 레벨 표시 변경, 이동은 Warp/걷기): " + String.join(", ", markers));
			}
			if (!r.unreachableVolumes.isEmpty()) {
				lines.add("내부 점 없는 볼륨: " + String.join(", ", r.unreachableVolumes));
			}
			if (!r.forcedEvents.isEmpty()) {
				lines.add("강제 이벤트: " + r.forcedEvents.keySet() + (r.forcedGroups.isEmpty() ? "" : ", 강제 오브젝트 그룹: " + r.forcedGroups));
			}
			if (!r.gotos.isEmpty()) {
				lines.add("Goto: " + r.gotos.entrySet().stream().map(e -> e.getKey() + (e.getValue() ? "" : " (없음)")).collect(Collectors.joining(", ")));
			}
			r.exceptions.forEach(x -> lines.add("예외: " + x));
			r.unbalanced.stream().limit(5).forEach(x -> lines.add("스택: " + x));
			if (r.heroDeathZones > 0) {
				lines.add("영웅 데스존 진입 " + r.heroDeathZones + "회 (검증 보행은 데스존도 밟는다)");
			}
			if (r.missing.size() > 4) {
				lines.add("누락 리소스: " + String.join(", ", r.missing));
			}
			if (lines.isEmpty()) {
				continue;
			}
			o.append("### ").append(r.level).append("\n");
			lines.forEach(l -> o.append("- ").append(l).append('\n'));
			o.append('\n');
		}
		return o.toString();
	}

	private static List<String> pathOf(Map<String, String> prev, String to) {
		List<String> out = new ArrayList<>();
		if (prev.containsKey(to)) {
			for (String c = to; c != null; c = prev.get(c)) {
				out.addFirst(c);
			}
		}
		return out;
	}

	private static String join(List<String> l) {
		return l.isEmpty() ? "없음" : String.join(", ", l);
	}

	private static String edge(Edge e) {
		String k = switch (e.kind()) {
			case SEAMLESS -> "~";
			case WALK -> "≈";
			case WARP -> "⇒";
			case LOAD -> "→";
		};
		return k + e.target() + (e.forced() ? "*" : "") + (e.targetExists() ? "" : " **없음**");
	}

	private static String arrival(Arrival a) {
		return (a.kind() == devple.gow.engine.game.LevelLink.Kind.SEAMLESS ? "~" : a.kind() == devple.gow.engine.game.LevelLink.Kind.WALK ? "≈" : "⇒") + a.from() + (a.ok() ? "" : " **" + a.detail() + "**");
	}

	private static String spawn(Spawn s) {
		return s.templateId() + (s.resolvedBy() == null ? " **?**" : "stand-in".equals(s.resolvedBy()) ? " (대역)"
				: s.model() == null ? " (모델 없음)" : "level".equals(s.resolvedBy()) ? " (레벨)" : "");
	}

	private static String errors(LevelResult r) {
		List<String> l = new ArrayList<>();
		if (!r.exceptions.isEmpty()) {
			l.add("예외 " + r.exceptions.size());
		}
		if (!r.unbalanced.isEmpty()) {
			l.add("스택 " + r.unbalanced.size());
		}
		if (!r.badWads.isEmpty()) {
			l.add("WAD " + String.join("/", r.badWads));
		}
		if (r.gotos.containsValue(false)) {
			l.add("Goto 없음");
		}
		return String.join(", ", l);
	}
}
