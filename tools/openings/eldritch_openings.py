#!/usr/bin/env python3
"""Generates the three eldritch inventory openings (ExcellentCrates 6.6.1-catcraft.6 or newer).

    python3 tools/openings/eldritch_openings.py [output dir]   (default: openings/inventory)

Writes abyssal_eye.yml, the_writhing.yml and summoning_rite.yml. Every timing (sound cues, how long the
side animations run, the skip window) is worked out from a copy of the plugin's spinner tick logic
(AbstractSpinner.tick/isSpinTime/onSpin), so the cues land on the reel's real moves. The script stops
with an error if a layout breaks a rule: the win slot must be on the reward path, launch animations must
stop before the reel does and stay off the reward path, and no reveal may draw over the prize.
"""

import math
import sys
from pathlib import Path

import yaml

COLS = 9


def slot(row, col):
    return row * COLS + col


# ---------------------------------------------------------------------------------------------------------
# Spinner timing: a copy of AbstractSpinner's tick logic (catcraft.6, incl. burst steps).
# ---------------------------------------------------------------------------------------------------------

def spin_ticks(delay, steps):
    """Local ticks (0 = the spinner's first tick) of every move. steps: (amount, interval[, burst])."""
    steps = [tuple(s) + (1,) * (3 - len(s)) for s in steps]
    queue = list(steps)
    ticks, tick, tick_count, spin_delay = [], 0, 0, delay
    current = queue.pop(0)
    step_count = 0
    while current is not None:
        if spin_delay > 0:
            spin_delay -= 1
            spin = False
        else:
            spin = tick_count == 0 or tick_count % current[1] == 0
        if spin:
            step = current
            for _ in range(step[2]):
                ticks.append(tick)
                step_count += 1
                if step_count >= current[0]:
                    current = queue.pop(0) if queue else None
                    step_count, tick_count = 0, 0
                if current is not step:
                    break
        tick_count += 1
        tick += 1
    return ticks


def total_spins(steps):
    return sum(s[0] for s in steps)


def step_str(step):
    if len(step) == 3 and step[2] > 1:
        return f'{step[0]}:{step[1]}:{step[2]}'
    return f'{step[0]}:{step[1]}'


def slots_str(slots):
    return ','.join(str(s) for s in slots)


# ---------------------------------------------------------------------------------------------------------
# Config builders
# ---------------------------------------------------------------------------------------------------------

def sound(name, volume, pitch):
    return f'minecraft:{name};{volume:g};{round(pitch, 2):g}'


def anim(spinner_id, mode, slots, steps, delay=0, snd=None):
    data = {
        'SpinnerId': spinner_id,
        'Mode': mode,
        'Slots': slots_str(slots),
    }
    if snd:
        data['Sound'] = snd
    data['Spins'] = [step_str(s) for s in steps]
    data['SpinDelay'] = delay
    return data


def cue(snd, delay):
    """A sound on its own: a silent spinner that moves once, on local tick `delay`."""
    return anim('silent', 'INDEPENDENT', [-1], [(1, 1)], delay, snd)


def pane(colour, glint=False):
    item = {'Chance': 1.0, 'Material': f'{colour}_stained_glass_pane', 'Hide_Tooltip': True}
    if glint:
        item['Enchant_Glint'] = True
    return item


def item(material, chance=1.0, glint=False):
    data = {'Chance': float(chance), 'Material': material, 'Hide_Tooltip': True}
    if glint:
        data['Enchant_Glint'] = True
    return data


def weighted(**items):
    """id=(item dict, weight)."""
    out = {}
    for key, (data, weight) in items.items():
        data = dict(data)
        data['Chance'] = float(weight)
        out[key] = data
    return {'Items': out}


def single(data):
    return {'Items': {'item': data}}


SILENT = {'Items': {'none': {'Chance': 1.0, 'Material': 'air'}}}


class Opening:
    def __init__(self, opening_id, rows, title, win_slot, header):
        self.id = opening_id
        self.size = rows * COLS
        self.rows = rows
        self.title = title
        self.win_slot = win_slot
        self.header = header
        self.launch = {}
        self.reward = {}
        self.reveals = {}
        self.defaults = {}
        self.spinners = {'silent': SILENT}
        self.reward_path = []
        self.reel_stop = 0
        self.pause = 50

    # Launch ------------------------------------------------------------------------------------------
    def add_reward(self, name, spinner_id, mode, slots, steps, delay=0):
        self.reward[name] = anim(spinner_id, mode, slots, steps, delay)
        self.reward_path = list(slots)
        self.reel_ticks = spin_ticks(delay, steps)
        self.reel_stop = self.reel_ticks[-1]
        self.reel_steps = steps
        self.reel_delay = delay

    def add_launch(self, name, data):
        self.launch[name] = data

    def run_until_stop(self, name, spinner_id, mode, slots, delay, interval):
        """An animation that keeps moving every `interval` ticks and stops on (or just before) the reel."""
        amount = max(1, (self.reel_stop - delay) // interval + 1)
        while spin_ticks(delay, [(amount, interval)])[-1] > self.reel_stop:
            amount -= 1
        self.add_launch(name, anim(spinner_id, mode, slots, [(amount, interval)], delay))

    def move_ticks(self):
        """One entry per tick the reel moves on (a burst tick counts once)."""
        return sorted(set(self.reel_ticks))

    # Reveal ------------------------------------------------------------------------------------------
    def reveal(self, reveal_id, match, skip_sounds):
        data = {}
        if match is not None:
            data['Match'] = match
        data['Skip_Sounds'] = skip_sounds
        data['ANIMATION'] = {}
        self.reveals[reveal_id] = data
        return data['ANIMATION']

    # Output ------------------------------------------------------------------------------------------
    def check(self):
        def fail(message):
            raise SystemExit(f'{self.id}: {message}')

        path = self.reward_path
        if self.win_slot not in path:
            fail('win slot is not on the reward path')
        if len(set(path)) != len(path):
            fail('reward path repeats a slot')
        for group, spinners in [('launch', self.launch), *((f'reveal {k}', v['ANIMATION']) for k, v in self.reveals.items())]:
            for name, data in spinners.items():
                slots = [int(s) for s in str(data['Slots']).split(',')]
                if slots != [-1] and any(s < 0 or s >= self.size for s in slots):
                    fail(f'{group} spinner {name} has a slot outside the window')
                if data['SpinnerId'] not in self.spinners:
                    fail(f'{group} spinner {name} uses unknown spinner {data["SpinnerId"]}')
                if group != 'launch' and self.win_slot in slots:
                    fail(f'{group} spinner {name} draws over the prize')
                if group == 'launch' and slots != [-1]:
                    if set(slots) & set(path):
                        fail(f'launch spinner {name} draws on the reward path')
                    steps = [tuple(int(v) for v in s.split(':')) for s in data['Spins']]
                    last = spin_ticks(int(data['SpinDelay']), steps)[-1]
                    if last > self.reel_stop:
                        fail(f'launch spinner {name} still moves after the reel stops ({last} > {self.reel_stop})')
        for default in self.defaults.values():
            if any(s < 0 or s >= self.size for s in default['Slots_list']):
                fail('default item slot outside the window')

    def to_yaml(self):
        self.check()
        defaults = {}
        for key, value in self.defaults.items():
            defaults[key] = {'Item': value['Item'], 'Slots': slots_str(value['Slots_list'])}
        config = {
            'Settings': {
                'Menu_Type': f'generic_9x{self.rows}',
                'Title': self.title,
                'Mode': 'NORMAL',
                'WinSlots': str(self.win_slot),
                'Max_Ticks_To_Skip': self.reel_stop,
                'Completion_Pause_Ticks': self.pause,
                'RunOnLaunch': {'ANIMATION': self.launch, 'REWARD': self.reward},
                'Reveals': self.reveals,
            },
            'Content': {'Default': defaults},
            'Spinners': {
                'ANIMATION': self.spinners,
                'REWARD': {
                    'default': {'Rarities': ['*']},
                    'showcase': {'Rarities': ['*'], 'Showcase_Broadcast_Rewards': True},
                },
            },
        }
        body = yaml.dump(config, sort_keys=False, allow_unicode=True, width=1000, default_flow_style=False)
        header = ''.join(f'# {line}\n' if line else '#\n' for line in self.header)
        header += f'# Generated by tools/openings/eldritch_openings.py (reel stops on tick {self.reel_stop}).\n'
        return header + body

    def default(self, key, data, slots):
        self.defaults[key] = {'Item': data, 'Slots_list': list(slots)}


def falling(start, end, count):
    if count <= 1:
        return [start]
    return [start + (end - start) * i / (count - 1) for i in range(count)]


def add_move_cues(opening, name, volume, pitch_from, pitch_to, heartbeat_last=3):
    moves = opening.move_ticks()
    for i, (tick, pitch) in enumerate(zip(moves, falling(pitch_from, pitch_to, len(moves)))):
        opening.add_launch(f'{name}_{i}', cue(sound(name_sound(name), volume, pitch), tick))
    for i, tick in enumerate(moves[-heartbeat_last:]):
        opening.add_launch(f'heartbeat_{i}', cue(sound('entity.warden.heartbeat', 1, 1), tick))


SOUND_BY_CUE = {}


def name_sound(name):
    return SOUND_BY_CUE[name]


# ---------------------------------------------------------------------------------------------------------
# 1. Abyssal Eye
# ---------------------------------------------------------------------------------------------------------

def abyssal_eye():
    o = Opening('abyssal_eye', 5, '<dark_purple>◉</dark_purple> %crate_name% <dark_purple>◉</dark_purple>', 22, [
        '"Abyssal Eye" crate opening for ExcellentCrates 6.6.1-catcraft.6 or newer. Put it in openings/inventory/.',
        'Pitch black, a heartbeat, then an eye opens: the slit widens from the middle row out. Rewards circle the',
        'iris and are swallowed by the pupil, slowing as the heartbeat closes in; the prize stops in the pupil.',
        'Normal win: the eye blinks shut around the prize, then opens with a glowing purple iris.',
        'Ultra: the eye turns bloodshot (red flicker, elder guardian curse), the iris burns magenta and two',
        'end-rod beams stare out to the sides. The crate\'s ultras pass through the iris mid-spin.',
    ])
    iris = [30, 21, 12, 13, 14, 23, 32, 31, 22]          # clockwise round the iris, then into the pupil
    ring = iris[:-1]
    sclera_mid = [19, 20, 24, 25]
    sclera_out = [11, 15, 29, 33]
    sclera = sclera_mid + sclera_out
    rim = [2, 3, 4, 5, 6, 10, 16, 18, 26, 28, 34, 38, 39, 40, 41, 42]   # the eyelids' edge round the almond
    edges = [18, 26]                                      # the eye's corners, where the stare beams come out
    lid = [s for s in range(45) if s not in iris and s not in sclera and s not in rim]
    row1, row3 = list(range(9, 18)), list(range(27, 36))
    row2_open = [s for s in range(18, 27) if s != 22]

    o.spinners.update({
        'dark': single(pane('black')),
        'white': single(pane('white')),
        'light': single(pane('light_gray')),
        'iris': single(pane('purple', glint=True)),
        'rim': single(pane('purple')),
        'iris_burn': single(pane('magenta', glint=True)),
        'bloodshot': single(pane('red', glint=True)),
        'stare': single(item('end_rod', glint=True)),
        'lid_pulse': weighted(black=(pane('black'), 16), shade=(pane('gray'), 1), sculk=(item('sculk_vein'), 1)),
        'sclera_pulse': weighted(white=(pane('white'), 10), grey=(pane('light_gray'), 3), red=(pane('red'), 1)),
        'blood_flicker': weighted(red=(pane('red', glint=True), 3), black=(pane('black'), 2), magenta=(pane('magenta'), 1)),
        'lid_burn': weighted(black=(pane('black'), 5), crying=(item('crying_obsidian'), 2), vein=(pane('purple'), 1)),
    })
    o.default('dark', pane('black'), range(45))

    o.add_reward('iris', 'showcase', 'SEQUENTAL', iris,
                 [(14, 1), (6, 2), (4, 3), (3, 4), (2, 6), (1, 8), (1, 10), (1, 13)], delay=14)
    stop = o.reel_stop

    # The eye opens (ticks 0-14), then the lids and the white of the eye pulse until the reel stops.
    o.add_launch('heartbeat_open_0', cue(sound('entity.warden.heartbeat', 1, 0.8), 0))
    o.add_launch('heartbeat_open_1', cue(sound('entity.warden.heartbeat', 1, 0.8), 8))
    o.add_launch('ambience', cue(sound('ambient.soul_sand_valley.mood', 1, 0.7), 0))
    o.add_launch('shriek', cue(sound('block.sculk_shrieker.shriek', 0.6, 0.6), 6))
    o.add_launch('slit', anim('light', 'SYNCRHONIZED', sclera_mid, [(1, 1)], 6))
    o.add_launch('slit_white', anim('white', 'SYNCRHONIZED', sclera_mid, [(1, 1)], 10))
    o.add_launch('open', anim('light', 'SYNCRHONIZED', sclera_out, [(1, 1)], 10))
    o.add_launch('open_white', anim('white', 'SYNCRHONIZED', sclera_out, [(1, 1)], 13))
    o.add_launch('rim_mid', anim('rim', 'SYNCRHONIZED', [10, 16, 18, 26, 28, 34], [(1, 1)], 8))
    o.add_launch('rim_out', anim('rim', 'SYNCRHONIZED', [2, 3, 4, 5, 6, 38, 39, 40, 41, 42], [(1, 1)], 12))
    o.run_until_stop('lid_pulse', 'lid_pulse', 'RANDOM', lid, 14, 4)
    o.run_until_stop('sclera_pulse', 'sclera_pulse', 'RANDOM', sclera, 16, 5)

    SOUND_BY_CUE['move'] = 'block.note_block.bass'
    add_move_cues(o, 'move', 0.8, 1.2, 0.6)

    # Ultra: bloodshot, burning iris, stare.
    ultra = o.reveal('ultra', {'Broadcast': True}, [
        sound('entity.elder_guardian.curse', 1, 0.8), sound('entity.player.levelup', 1, 0.6)])
    ultra['curse'] = cue(sound('entity.elder_guardian.curse', 1, 0.8), 0)
    ultra['iris_burn'] = anim('iris_burn', 'SYNCRHONIZED', ring, [(1, 1)], 0)
    ultra['bloodshot_flicker'] = anim('blood_flicker', 'RANDOM', sclera, [(10, 2)], 0)
    ultra['wide_open'] = anim('dark', 'SYNCRHONIZED', [s for s in rim if s not in edges], [(1, 1)], 0)
    ultra['lid_burn'] = anim('lid_burn', 'RANDOM', lid, [(8, 3)], 2)
    ultra['bloodshot'] = anim('bloodshot', 'SYNCRHONIZED', sclera, [(1, 1)], 20)
    ultra['lid_dark'] = anim('dark', 'SYNCRHONIZED', lid, [(1, 1)], 26)
    ultra['stare'] = anim('stare', 'SYNCRHONIZED', edges, [(1, 1)], 20)
    ultra['stare_sound'] = cue(sound('block.beacon.power_select', 1, 0.6), 20)
    ultra['fanfare'] = cue(sound('entity.player.levelup', 1, 0.6), 22)
    ultra['bell'] = cue(sound('block.bell.resonate', 1, 0.7), 24)

    # Normal: blink shut around the prize, then open with a glowing iris.
    normal = o.reveal('normal', None, [
        sound('block.sculk_catalyst.bloom', 1, 0.8), sound('entity.player.levelup', 0.8, 0.7)])
    normal['bloom'] = cue(sound('block.sculk_catalyst.bloom', 1, 0.8), 0)
    normal['lids_close'] = anim('dark', 'SYNCRHONIZED', row1 + row3, [(1, 1)], 0)
    normal['shut'] = anim('dark', 'SYNCRHONIZED', row2_open, [(1, 1)], 2)
    normal['blink'] = cue(sound('entity.warden.heartbeat', 1, 1.2), 2)
    normal['open_mid'] = anim('white', 'SYNCRHONIZED', sclera_mid, [(1, 1)], 8)
    normal['iris_mid'] = anim('iris', 'SYNCRHONIZED', [21, 23], [(1, 1)], 8)
    normal['open_out'] = anim('white', 'SYNCRHONIZED', sclera_out, [(1, 1)], 10)
    normal['iris_out'] = anim('iris', 'SYNCRHONIZED', [12, 13, 14, 30, 31, 32], [(1, 1)], 10)
    normal['rim'] = anim('rim', 'SYNCRHONIZED', [10, 16, 18, 26, 28, 34], [(1, 1)], 10)
    normal['resonate'] = cue(sound('block.amethyst_block.resonate', 1, 0.8), 10)
    normal['chime'] = cue(sound('entity.player.levelup', 0.8, 0.7), 12)
    return o


# ---------------------------------------------------------------------------------------------------------
# 2. The Writhing
# ---------------------------------------------------------------------------------------------------------

def the_writhing():
    o = Opening('the_writhing', 6, '<dark_aqua>≈</dark_aqua> %crate_name% <dark_aqua>≈</dark_aqua>', 4, [
        '"The Writhing" crate opening for ExcellentCrates 6.6.1-catcraft.6 or newer. Put it in openings/inventory/.',
        'Two tentacles squirm up from the bottom of the window, pulsing purple, while sculk creeps in the dark.',
        'A third, middle tentacle drags the rewards up its coils towards the top; it slows, a drone falls in',
        'pitch, heartbeat on the final pulls, and the prize lands at the top centre.',
        'Normal win: the tentacles retract into the deep and the prize glows. Ultra: every tentacle lashes',
        'white and magenta (sonic boom), then crying obsidian blooms outwards from the prize.',
        'The crate\'s ultras ride up the middle tentacle mid-spin.',
    ])
    middle = [49, 40, 39, 30, 31, 22, 23, 14, 13, 4]
    left = [46, 37, 36, 27, 28, 19, 20, 11, 10, 1]
    right = [52, 43, 44, 35, 34, 25, 24, 15, 16, 7]
    background = [s for s in range(54) if s not in middle + left + right]
    tentacles = left + right

    o.spinners.update({
        'dark': single(pane('black')),
        'glow': single(pane('purple', glint=True)),
        'glow2': single(pane('magenta', glint=True)),
        'lash': single(pane('white', glint=True)),
        'crying': single(item('crying_obsidian')),
        'tentacle': weighted(flesh=(pane('purple'), 6), sucker=(pane('magenta'), 1), shine=(pane('purple', glint=True), 1)),
        'sculk': weighted(black=(pane('black'), 14), sculk=(item('sculk'), 2), vein=(item('sculk_vein'), 1)),
    })
    o.default('dark', pane('black'), range(54))

    o.add_reward('drag', 'showcase', 'SEQUENTAL', middle,
                 [(20, 1, 2), (12, 1), (6, 2), (3, 3), (2, 5), (1, 7), (1, 9), (1, 12)], delay=8)
    stop = o.reel_stop

    o.add_launch('squirt', cue(sound('entity.squid.squirt', 1, 0.6), 0))
    o.add_launch('ambience', cue(sound('block.conduit.ambient', 1, 0.6), 0))
    o.add_launch('squirt_2', cue(sound('entity.glow_squid.squirt', 0.8, 0.5), 8))
    # Grow both tentacles (one slot a tick), then keep pulsing colour up them until the reel stops.
    o.add_launch('grow_left', anim('tentacle', 'SEQUENTAL', left, [(10, 1)], 0))
    o.add_launch('grow_right', anim('tentacle', 'SEQUENTAL', right, [(10, 1)], 0))
    o.run_until_stop('writhe_left', 'tentacle', 'SEQUENTAL', left, 11, 2)
    o.run_until_stop('writhe_right', 'tentacle', 'SEQUENTAL', right, 11, 2)
    o.run_until_stop('sculk', 'sculk', 'RANDOM', background, 4, 3)

    SOUND_BY_CUE['drone'] = 'block.note_block.didgeridoo'
    add_move_cues(o, 'drone', 0.5, 1.0, 0.5)

    retract = list(reversed(middle[:-1]))                 # tip first, so the black runs down to the base
    ring1 = [3, 5, 12, 13, 14]

    ultra = o.reveal('ultra', {'Broadcast': True}, [
        sound('entity.warden.sonic_boom', 0.6, 1), sound('entity.player.levelup', 1, 0.6)])
    ultra['boom'] = cue(sound('entity.warden.sonic_boom', 0.6, 1), 0)
    for i, spinner in enumerate(['lash', 'glow2', 'lash', 'glow']):
        ultra[f'lash_{i}'] = anim(spinner, 'SYNCRHONIZED', tentacles + middle[:-1], [(1, 1)], i * 3)
    for d in range(1, 6):
        ring = [s for s in range(54) if s != 4 and max(abs(s // 9 - 0), abs(s % 9 - 4)) == d]
        ultra[f'bloom_{d}'] = anim('crying', 'SYNCRHONIZED', ring, [(1, 1)], 10 + 2 * d)
    ultra['anchor'] = cue(sound('block.respawn_anchor.charge', 1, 0.6), 12)
    ultra['anchor_2'] = cue(sound('block.respawn_anchor.set_spawn', 1, 0.7), 20)
    ultra['halo'] = anim('glow2', 'SYNCRHONIZED', ring1, [(1, 1)], 24)
    ultra['fanfare'] = cue(sound('entity.player.levelup', 1, 0.6), 24)

    normal = o.reveal('normal', None, [
        sound('entity.squid.squirt', 1, 0.5), sound('entity.player.levelup', 0.8, 0.8)])
    normal['squirt'] = cue(sound('entity.squid.squirt', 1, 0.5), 0)
    normal['retract_left'] = anim('dark', 'SEQUENTAL', list(reversed(left)), [(10, 1)], 0)
    normal['retract_right'] = anim('dark', 'SEQUENTAL', list(reversed(right)), [(10, 1)], 0)
    normal['retract_middle'] = anim('dark', 'SEQUENTAL', retract, [(9, 1)], 0)
    normal['background'] = anim('dark', 'SYNCRHONIZED', background, [(1, 1)], 0)
    normal['halo'] = anim('glow', 'SYNCRHONIZED', ring1, [(1, 1)], 10)
    normal['chime'] = cue(sound('entity.player.levelup', 0.8, 0.8), 10)
    return o


# ---------------------------------------------------------------------------------------------------------
# 3. Summoning Rite
# ---------------------------------------------------------------------------------------------------------

def summoning_rite():
    o = Opening('summoning_rite', 6, '<dark_purple>✦</dark_purple> %crate_name% <dark_purple>✦</dark_purple>', 13, [
        '"Summoning Rite" crate opening for ExcellentCrates 6.6.1-catcraft.6 or newer. Put it in openings/inventory/.',
        'A ritual circle: rewards sit on a ring round a crying-obsidian altar and the ring turns like a real wheel',
        '(LOOP mode, nothing new enters). As it slows, the runes round the edge light one by one, the last as it',
        'stops; soul lanterns flicker; the prize stops under the watching eye (top centre of the ring).',
        'Normal win: a white wave runs round the runes, the altar burns with soul fire and the ring goes dark',
        'except the prize. Ultra: blackout, then a portal swirls out from the altar (end portal, wither).',
        'The crate\'s ultras ride the ring but always stop on its far side.',
    ])
    ring = [11, 12, 13, 14, 15, 24, 33, 42, 41, 40, 39, 38, 29, 20]   # clockwise; 13 = under the eye
    altar = [21, 22, 23, 30, 31, 32]
    candles = [10, 19, 28, 37, 16, 25, 34, 43]
    runes = [5, 6, 7, 8, 17, 26, 35, 44, 53, 52, 51, 50, 49, 48, 47, 46, 45, 36, 27, 18, 9, 0, 1, 2, 3]
    eye = [4]
    corners = {0, 8, 45, 53}

    o.spinners.update({
        'dark': single(pane('black')),
        'white': single(pane('white', glint=True)),
        'rune': single(pane('purple', glint=True)),
        'rune2': single(pane('magenta', glint=True)),
        'echo': single(item('echo_shard', glint=True)),
        'soul_fire': single(pane('cyan', glint=True)),
        'lantern': single(item('soul_lantern')),
        'eye': single(item('ender_eye', glint=True)),
        'altar': weighted(crying=(item('crying_obsidian'), 4), obsidian=(item('obsidian'), 2), glow=(pane('purple', glint=True), 1)),
        'candles': weighted(lantern=(item('soul_lantern'), 3), torch=(item('soul_torch'), 2), out=(pane('black'), 2)),
        'portal': weighted(crying=(item('crying_obsidian'), 2), magenta=(pane('magenta', glint=True), 1),
                           purple=(pane('purple', glint=True), 1)),
    })
    o.default('dark', pane('black'), runes + ring)
    o.default('altar', item('crying_obsidian'), altar)
    o.default('candles', item('soul_lantern'), candles)
    o.default('eye', item('ender_eye'), eye)

    o.add_reward('wheel', 'showcase', 'LOOP', ring,
                 [(1, 1), (24, 1), (10, 2), (6, 3), (3, 5), (2, 7), (1, 10), (1, 13)], delay=6)
    stop = o.reel_stop
    fill = o.reel_ticks[0]

    o.add_launch('ambience', cue(sound('ambient.basalt_deltas.mood', 1, 0.6), 0))
    o.add_launch('summon', cue(sound('entity.evoker.prepare_summon', 0.8, 0.7), fill))
    o.run_until_stop('altar', 'altar', 'RANDOM', altar, 4, 4)
    o.run_until_stop('candles', 'candles', 'RANDOM', candles, 4, 3)
    # Light the runes one by one from the fill to the stop, the last on the reel's final move.
    for i, (rune, tick) in enumerate(zip(runes, falling(fill + 4, stop, len(runes)))):
        tick = round(tick)
        spinner = 'echo' if rune in corners else ('rune2' if i % 3 == 2 else 'rune')
        o.add_launch(f'rune_{i}', anim(spinner, 'INDEPENDENT', [rune], [(1, 1)], tick))
        o.add_launch(f'rune_{i}_sound', cue(sound('block.enchantment_table.use', 0.5, 0.6 + i * 0.04), tick))

    SOUND_BY_CUE['tick'] = 'block.amethyst_block.hit'
    add_move_cues(o, 'tick', 0.6, 1.6, 0.8)

    # Ultra: blackout, then a portal swirls outward from the altar.
    centre = (2.5, 4)
    others = [s for s in range(54) if s != 13]
    spiral = sorted(others, key=lambda s: (round(math.hypot(s // 9 - centre[0], s % 9 - centre[1]) * 1.4),
                                           math.atan2(s // 9 - centre[0], s % 9 - centre[1])))
    ultra = o.reveal('ultra', {'Broadcast': True}, [
        sound('block.end_portal.spawn', 0.8, 1), sound('entity.player.levelup', 1, 0.6)])
    ultra['blackout'] = anim('dark', 'SYNCRHONIZED', others, [(1, 1)], 0)
    ultra['dim'] = cue(sound('block.beacon.deactivate', 1, 0.6), 0)
    ultra['portal'] = anim('portal', 'SEQUENTAL', spiral, [(len(spiral), 1, 2)], 6)
    ultra['portal_sound'] = cue(sound('block.end_portal.spawn', 0.8, 1), 6)
    ultra['wither'] = cue(sound('entity.wither.spawn', 0.35, 1.2), 12)
    done = 6 + spin_ticks(0, [(len(spiral), 1, 2)])[-1] + 2
    ultra['runes'] = anim('rune', 'SYNCRHONIZED', runes, [(1, 1)], done)
    ultra['eye'] = anim('eye', 'SYNCRHONIZED', eye, [(1, 1)], done)
    ultra['fanfare'] = cue(sound('entity.player.levelup', 1, 0.6), done)

    # Normal: a white wave runs round the runes, soul fire on the altar, the ring goes dark but for the prize.
    normal = o.reveal('normal', None, [
        sound('entity.evoker.cast_spell', 1, 0.8), sound('entity.player.levelup', 0.8, 0.9)])
    normal['cast'] = cue(sound('entity.evoker.cast_spell', 1, 0.8), 0)
    normal['wave'] = anim('white', 'SEQUENTAL', runes, [(len(runes), 1)], 0)
    normal['relight'] = anim('rune', 'SEQUENTAL', runes, [(len(runes), 1)], 6)
    normal['soul_fire'] = anim('soul_fire', 'SYNCRHONIZED', altar, [(1, 1)], 2)
    normal['lanterns'] = anim('lantern', 'SYNCRHONIZED', candles, [(1, 1)], 2)
    normal['ring_dark'] = anim('dark', 'SYNCRHONIZED', [s for s in ring if s != 13], [(1, 1)], 4)
    normal['soul'] = cue(sound('particle.soul_escape', 1, 0.8), 4)
    normal['chime'] = cue(sound('entity.player.levelup', 0.8, 0.9), 8)
    normal['done'] = cue(sound('block.enchantment_table.use', 1, 1.4), len(runes) + 6)
    return o


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else 'openings/inventory')
    out.mkdir(parents=True, exist_ok=True)
    for build in (abyssal_eye, the_writhing, summoning_rite):
        opening = build()
        (out / f'{opening.id}.yml').write_text(opening.to_yaml(), encoding='utf-8')
        print(f'{opening.id}: reel stops on tick {opening.reel_stop}, {len(opening.launch)} launch spinners, '
              f'{sum(len(r["ANIMATION"]) for r in opening.reveals.values())} reveal spinners')


if __name__ == '__main__':
    main()
