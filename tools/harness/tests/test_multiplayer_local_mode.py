"""Create Game's items name the modes they start, and LOCAL (BOTS) alone
hosts a game that accepts no remote players, which is what lets bots join it
(menu_functions.c's multiplayer_mode_set and multiplayer_host)."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from harness import CHECK_FAILED, build, function, mutated, read, run


def generated(fault=None):
    source = read('port/linux/game/menu_functions.c')
    code = function(source, 'multiplayer_mode_set') + "\n" + function(source, 'multiplayer_host')
    if fault:
        code = mutated(code, *fault)
    return (('under_test.inc', code),)


@pytest.mark.parametrize('case', ['modes', 'hosts'])
def test_case(case):
    status, output = run(build('multiplayer_local_mode', generated()), case)
    assert status == 0, output


def test_negative_control():
    fault = ('multiplayer.mode == _multiplayer_mode_host_local',
             'multiplayer.mode == _multiplayer_mode_host_lan')
    status, output = run(build('multiplayer_local_mode', generated(fault)), 'hosts')
    assert status == CHECK_FAILED, output
