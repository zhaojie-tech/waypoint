# Pull request

## Summary

<!-- What does this PR change, and why? Link related issues. -->

## Type

- [ ] Bug fix
- [ ] Contract / checker implementation
- [ ] Calibration bed / mutant
- [ ] CLI / API / adapter
- [ ] p3 / p4 experiment or evidence
- [ ] Documentation
- [ ] Release engineering / CI

## Test plan

<!-- Paste the commands you ran and their results. -->

- [ ] `./mvnw -B -q verify` passes (JUnit 5)
- [ ] `./gate.sh` exits 0 (and `./p4/gate.sh` where relevant)
- [ ] `./scripts/verify-docs.sh --skip-gate` passes
- [ ] `./scripts/update-results.sh` was run if any gate metric changed
- [ ] New/changed behaviour is covered by a test, gate negative control, or mutant

## Checklist

- [ ] No build artifacts (`target/`, `build/`, `*.class`, `p3/classes`, `p4/classes`) are tracked.
- [ ] No generated reports were added to tracked paths; gate output goes to `build/` or `results/`.
- [ ] README marker blocks (`<!-- gate:key -->…<!-- /gate:key -->`) match `build/gate-result.json`.
- [ ] Documentation states what is not observable / not implemented (no claim without evidence).
- [ ] No credentials, endpoints, tokens, or private data are included.
- [ ] Commits are signed off (`git commit -s`) under the Developer Certificate of Origin 1.1.
- [ ] Commit messages describe verified facts, not intentions.
