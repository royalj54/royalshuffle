"""Focused research correctness checks, not product acceptance tests."""
import unittest
import ast
import hashlib
import json
import os
from collections import Counter
from dataclasses import replace
from pathlib import Path
import random
from unittest.mock import patch

from research.balanced_rotation.viability import (
    Candidate, GenerationHistory, Scenario, Session, Track, aggregate,
    candidates, duration_prefix, heard_tracks, load_separator, make_tracks,
    scenarios, separate, simulate,
)


class ViabilityTests(unittest.TestCase):
    def external_reference(self, variable):
        configured = os.environ.get(variable)
        if configured is None:
            self.skipTest(f"Set {variable} to the external Windows reference file; see README.md.")
        self.assertTrue(configured.strip(), f"{variable} must name a reference file.")
        path = Path(configured)
        self.assertTrue(path.is_file(), f"{variable} does not name an existing reference file: {path}")
        return path

    def test_prefix_crossing_full_short_source_duplicates(self):
        a = Track("a","same",120000)
        b = Track("b","same",120000)
        c = Track("c","other",120000)
        self.assertEqual(duration_prefix([a,b,c],3),[a,b])
        self.assertEqual(duration_prefix([a,b,c],None),[a,b,c])
        self.assertEqual(duration_prefix([a,b,c],60),[a,b,c])

    def test_consumption_no_overshoot_abandon_and_threshold(self):
        tracks = [Track("a","a",120000),Track("b","b",120000)]
        self.assertEqual(heard_tracks(tracks,0,random.Random(1)),[])
        self.assertEqual(heard_tracks(tracks,0.9,random.Random(1)),[])
        self.assertEqual(heard_tracks(tracks,2.5,random.Random(1)),tracks[:1])
        self.assertEqual(heard_tracks(tracks,3,random.Random(1)),tracks)

    def test_last_n_and_rolling_boundary(self):
        tracks = make_tracks(3,1)
        history = GenerationHistory(Candidate("n",last_n=1,strength=.35))
        history.observe_generated(tracks[:1],0)
        self.assertEqual(history.weights(tracks,1),[.35,1,1])
        history.observe_generated(tracks[1:2],1)
        self.assertEqual(history.weights(tracks,2),[1,.35,1])
        history = GenerationHistory(Candidate("w",horizon="rolling",strength=.35))
        history.observe_generated(tracks,0)
        self.assertEqual(history.weights(tracks,7),[1,1,1])

    def test_full_binary_and_count_have_equal_weights(self):
        tracks = make_tracks(10,1)
        for c in (Candidate("binary"),Candidate("count",signal="count",horizon="count")):
            h = GenerationHistory(c)
            for i in range(4):
                h.observe_generated(tracks,i)
                weights = h.weights(tracks,i+1)
                self.assertEqual(len(set(weights)),1)

    def test_elapsed_decay_and_repeated_weight_query_are_nonmutating(self):
        tracks = make_tracks(10,1)
        h = GenerationHistory(Candidate("time",signal="duration",horizon="elapsed"))
        h.observe_generated(tracks,0)
        early = h.weights(tracks,0)
        late = h.weights(tracks,90)
        self.assertGreater(max(early)-min(early),max(late)-min(late))
        self.assertEqual(early,h.weights(tracks,0))

    def test_generated_signal_never_receives_consumption(self):
        sessions = tuple(Session(i,60,0) for i in range(5))
        abandoned = Scenario("test",50,sessions)
        listened = replace(abandoned,sessions=tuple(replace(s,consumed=45) for s in sessions))
        original = GenerationHistory.observe_generated
        for candidate in candidates():
            if candidate.signal == "oracle":
                continue
            captures = []
            def spy(instance,generated,day):
                captures.append(tuple(t.occurrence_id for t in generated))
                return original(instance,generated,day)
            with patch.object(GenerationHistory,"observe_generated",spy):
                simulate(abandoned,candidate,1)
                first = list(captures)
                captures.clear()
                simulate(listened,candidate,1)
                self.assertEqual(first,captures,candidate.name)

    def test_reproducible_and_oracle_distinct(self):
        s = Scenario("test",50,tuple(Session(i,60,0) for i in range(5)))
        c = candidates()[5]
        self.assertEqual(simulate(s,c,7),simulate(s,c,7))
        self.assertEqual(simulate(s,c,7)["heard_events"],0)

    def test_mutation_and_aggregate(self):
        s = Scenario("test",50,tuple(Session(i,60,45) for i in range(6)),mutation=True)
        rows = [simulate(s,c,seed) for seed in (0,1) for c in candidates()[:2]]
        report = aggregate(rows)
        self.assertEqual(len(report),2)
        self.assertTrue(all(r["added_coverage"] is not None for r in report))
        self.assertEqual(next(r for r in report if r["candidate"]=="true_random")["delta_coverage"],0)

    def test_matrix_has_required_sizes_and_modes(self):
        matrix = scenarios(4)
        self.assertTrue({50,100,300,500,1000} <= {s.size for s in matrix})
        self.assertTrue(any(s.separation for s in matrix))
        self.assertTrue(any(s.playback=="shuffle" for s in matrix))
        self.assertTrue(any(s.name=="custom37" for s in matrix))

    def test_downstream_production_separation_preserves_duplicates_and_duration(self):
        path = self.external_reference("ROYALSHUFFLE_RESEARCH_ARTIST_MODULE")
        manifest = json.loads((Path(__file__).parent / "results/viability/manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), manifest["artist_module_sha256"],
                         "Artist Separation reference differs from the historical study.")
        function = load_separator(path)
        tracks = make_tracks(50,7,"dominant")
        t = tracks[0]
        tracks.append(Track("duplicate",t.track_id,t.duration_ms,t.artist_id))
        result = separate(tracks,random.Random(1),function)
        self.assertEqual(Counter(t.occurrence_id for t in tracks),Counter(t.occurrence_id for t in result))
        self.assertEqual(sum(t.duration_ms for t in tracks),sum(t.duration_ms for t in result))
        self.assertEqual({id(t) for t in tracks},{id(t) for t in result})

    def test_prefix_matches_current_production_function_without_importing_app(self):
        path = self.external_reference("ROYALSHUFFLE_RESEARCH_APP_MODULE")
        tree = ast.parse(path.read_text(encoding="utf-8"))
        function = next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=="apply_session_length")
        namespace = {"SessionLengthError":ValueError}
        exec(compile(ast.Module(body=[function],type_ignores=[]),str(path),"exec"),namespace)
        tracks = make_tracks(100,4)
        items = [{"duration_ms":t.duration_ms,"track":t} for t in tracks]
        for minutes in (1,37,60,120,180,1000):
            production, total = namespace["apply_session_length"](items,minutes*60000)
            self.assertEqual([i["track"] for i in production],duration_prefix(tracks,minutes))
            self.assertEqual(total,sum(i["duration_ms"] for i in production))


if __name__ == "__main__":
    unittest.main()
