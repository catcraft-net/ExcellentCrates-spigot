#!/usr/bin/env python3
"""Generates the three eldritch inventory openings (ExcellentCrates 6.6.1-catcraft.6 or newer).

    python3 tools/openings/eldritch_openings.py [output dir]   (default: openings/inventory)

Writes abyssal_eye.yml, the_writhing.yml and summoning_rite.yml. Every timing (sound cues, how long the
side animations run, the skip window) is worked out from a copy of the plugin's spinner tick logic
(AbstractSpinner.tick/isSpinTime/onSpin), so the cues land on the reel's real moves. The script stops
with an error if a layout breaks a rule: the win slot must be on the reward path, extra (display-only) reels
must stop with the main one and stay off its path, launch animations must stay off every reel, and no
reveal may draw over the prize.

Design rule (from review): show plenty of rewards at once (long reels) and keep the decoration calm: no
RANDOM-mode flicker or mixed-item spinners; every effect is one item moving in order (SEQUENTAL waves or
SYNCRHONIZED steps).
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
        self.display_paths = []
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

    def add_display_reel(self, name, slots):
        """A reel that shows rewards but awards nothing (no win slot on it), moving in step with the main one.
        RewardSpinner only adds a reward for a win slot on its own path, so this never changes what is won."""
        self.reward[name] = anim('default', 'SEQUENTAL', slots, self.reel_steps, self.reel_delay)
        self.display_paths.append(list(slots))

    def add_launch(self, name, data):
        self.launch[name] = data

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
        reels = [path] + self.display_paths
        every = [s for reel in reels for s in reel]
        if len(set(every)) != len(every):
            fail('reels repeat a slot or overlap')
        if any(s < 0 or s >= self.size for s in every):
            fail('reel slot outside the window')
        for name, data in self.reward.items():
            steps = [tuple(int(v) for v in s.split(':')) for s in data['Spins']]
            if spin_ticks(int(data['SpinDelay']), steps)[-1] != self.reel_stop:
                fail(f'reel {name} does not stop with the main reel')
        for name, data in {**self.launch, **{k: v for r in self.reveals.values() for k, v in r['ANIMATION'].items()}}.items():
            if data['SpinnerId'] != 'silent' and data['Mode'] == 'RANDOM':
                fail(f'spinner {name} flickers at random (keep effects in order)')
        for spinner_id, provider in self.spinners.items():
            if len(provider['Items']) > 1:
                fail(f'spinner {spinner_id} mixes items (keep effects to one item)')
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
                    if set(slots) & set(every):
                        fail(f'launch spinner {name} draws on a reel')
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


def add_move_cues(opening, sound_name, volume, pitch_from, pitch_to, heartbeat_last=3):
    """A note on every tick the reel moves (falling in pitch), heartbeats on the last moves."""
    moves = opening.move_ticks()
    for i, (tick, pitch) in enumerate(zip(moves, falling(pitch_from, pitch_to, len(moves)))):
        opening.add_launch(f'move_{i}', cue(sound(sound_name, volume, pitch), tick))
    for i, tick in enumerate(moves[-heartbeat_last:]):
        opening.add_launch(f'heartbeat_{i}', cue(sound('entity.warden.heartbeat', 1, 1), tick))


# ---------------------------------------------------------------------------------------------------------
# 1. Abyssal Eye
# ---------------------------------------------------------------------------------------------------------

def abyssal_eye():
    o = Opening('abyssal_eye', 5, '<dark_purple>◉</dark_purple> %crate_name% <dark_purple>◉</dark_purple>', 22, [
        '"Abyssal Eye" crate opening for ExcellentCrates 6.6.1-catcraft.6 or newer. Put it in openings/inventory/.',
        'A heartbeat in the dark, then purple eyelids open from the middle outwards. The whole eye is the reel:',
        '17 rewards whirl through it (top, left corner, middle, bottom) and are swallowed by the pupil, slowing',
        'as the heartbeat closes in; the prize stops in the pupil.',
        'Normal win: the eye blinks shut around the prize, then opens white with a glowing purple iris.',
        'Ultra: the eyelids turn red, red veins spread out from the pupil, the iris burns magenta and two end-rod',
        'beams stare out of the corners. The crate\'s ultras whirl through the eye mid-spin.',
    ])
    # Every slot of the almond-shaped eye, in the order rewards pass through it; 22 (the pupil) last.
    eye = [25, 15, 14, 13, 12, 11, 19, 20, 21, 29, 30, 31, 32, 33, 24, 23, 22]
    iris = [12, 13, 14, 21, 23, 30, 31, 32]
    sclera = [s for s in eye if s not in iris and s != 22]
    corners = [18, 26]
    lids_mid = [10, 16, 28, 34]
    lids_top = [2, 3, 4, 5, 6, 38, 39, 40, 41, 42]
    rim = corners + lids_mid + lids_top
    background = [s for s in range(45) if s not in eye and s not in rim]
    row1 = [s for s in range(9, 18) if s not in background]
    row3 = [s for s in range(27, 36) if s not in background]
    row2 = [s for s in range(18, 27) if s != 22]

    o.spinners.update({
        'dark': single(pane('black')),
        'lid': single(pane('purple')),
        'white': single(pane('white')),
        'iris': single(pane('purple', glint=True)),
        'iris_burn': single(pane('magenta', glint=True)),
        'lid_burn': single(pane('red', glint=True)),
        'vein': single(pane('red')),
        'stare': single(item('end_rod', glint=True)),
    })
    o.default('dark', pane('black'), background + rim)
    o.default('sclera', pane('white'), eye)

    o.add_reward('eye', 'showcase', 'SEQUENTAL', eye,
                 [(16, 1, 2), (10, 1), (6, 2), (4, 3), (3, 4), (2, 6), (1, 8), (1, 10), (1, 13)], delay=8)

    # The eyelids open from the corners and the middle outwards (ticks 0-6), then stay still.
    o.add_launch('heartbeat_open', cue(sound('entity.warden.heartbeat', 1, 0.8), 0))
    o.add_launch('ambience', cue(sound('ambient.soul_sand_valley.mood', 1, 0.7), 0))
    o.add_launch('shriek', cue(sound('block.sculk_shrieker.shriek', 0.6, 0.6), 2))
    o.add_launch('open_corners', anim('lid', 'SYNCRHONIZED', corners, [(1, 1)], 2))
    o.add_launch('open_mid', anim('lid', 'SYNCRHONIZED', lids_mid, [(1, 1)], 4))
    o.add_launch('open_top', anim('lid', 'SYNCRHONIZED', lids_top, [(1, 1)], 6))
    add_move_cues(o, 'block.note_block.bass', 0.8, 1.2, 0.6)

    # Ultra: red lids, red veins spreading out from the pupil, burning iris, stare.
    ultra = o.reveal('ultra', {'Broadcast': True}, [
        sound('entity.elder_guardian.curse', 1, 0.8), sound('entity.player.levelup', 1, 0.6)])
    ultra['curse'] = cue(sound('entity.elder_guardian.curse', 1, 0.8), 0)
    ultra['lids'] = anim('lid_burn', 'SYNCRHONIZED', lids_mid + lids_top, [(1, 1)], 0)
    veins = list(reversed(eye[:-1]))                      # from the pupil outwards
    ultra['veins'] = anim('vein', 'SEQUENTAL', veins, [(len(veins), 1)], 0)
    ultra['iris'] = anim('iris_burn', 'SYNCRHONIZED', iris, [(1, 1)], len(veins) + 2)
    ultra['stare'] = anim('stare', 'SYNCRHONIZED', corners, [(1, 1)], len(veins) + 2)
    ultra['stare_sound'] = cue(sound('block.beacon.power_select', 1, 0.6), len(veins) + 2)
    ultra['fanfare'] = cue(sound('entity.player.levelup', 1, 0.6), len(veins) + 4)
    ultra['bell'] = cue(sound('block.bell.resonate', 1, 0.7), len(veins) + 6)

    # Normal: blink shut around the prize, then open white with a glowing iris.
    normal = o.reveal('normal', None, [
        sound('block.sculk_catalyst.bloom', 1, 0.8), sound('entity.player.levelup', 0.8, 0.7)])
    normal['bloom'] = cue(sound('block.sculk_catalyst.bloom', 1, 0.8), 0)
    normal['close_outer'] = anim('dark', 'SYNCRHONIZED', row1 + row3, [(1, 1)], 0)
    normal['close_middle'] = anim('dark', 'SYNCRHONIZED', row2, [(1, 1)], 2)
    normal['blink'] = cue(sound('entity.warden.heartbeat', 1, 1.2), 2)
    normal['open_lids'] = anim('lid', 'SYNCRHONIZED', corners + lids_mid, [(1, 1)], 8)
    normal['open_white'] = anim('white', 'SYNCRHONIZED', sclera, [(1, 1)], 8)
    normal['open_iris'] = anim('iris', 'SYNCRHONIZED', iris, [(1, 1)], 8)
    normal['resonate'] = cue(sound('block.amethyst_block.resonate', 1, 0.8), 8)
    normal['chime'] = cue(sound('entity.player.levelup', 0.8, 0.7), 10)
    return o


# ---------------------------------------------------------------------------------------------------------
# 2. The Writhing
# ---------------------------------------------------------------------------------------------------------

def the_writhing():
    o = Opening('the_writhing', 6, '<dark_aqua>≈</dark_aqua> %crate_name% <dark_aqua>≈</dark_aqua>', 4, [
        '"The Writhing" crate opening for ExcellentCrates 6.6.1-catcraft.6 or newer. Put it in openings/inventory/.',
        'Three purple tentacles curl up out of the sculk at the bottom of the window. Rewards are dragged up all',
        'three in step (30 on screen at once); they slow, a drone falls in pitch, heartbeat on the final pulls,',
        'and the prize lands at the top of the middle tentacle. The side tentacles only show rewards: the prize',
        'is always the one on the middle tentacle.',
        'Normal win: the tentacles retract into the deep and the prize glows. Ultra: the tentacles flash white',
        'and magenta (sonic boom), crying obsidian blooms outwards from the prize and the tentacles glow purple.',
        'The crate\'s ultras ride up the middle tentacle mid-spin.',
    ])
    middle = [49, 40, 39, 30, 31, 22, 23, 14, 13, 4]
    left = [46, 37, 36, 27, 28, 19, 20, 11, 10, 1]
    right = [52, 43, 44, 35, 34, 25, 24, 15, 16, 7]
    tentacles = left + right + middle[:-1]
    deep = [45, 47, 48, 50, 51, 53]                       # the sculk the tentacles rise from
    background = [s for s in range(54) if s not in tentacles + [4] + deep]
    halo = [3, 5, 12, 13, 14]

    o.spinners.update({
        'dark': single(pane('black')),
        'glow': single(pane('purple', glint=True)),
        'glow2': single(pane('magenta', glint=True)),
        'flash': single(pane('white', glint=True)),
        'crying': single(item('crying_obsidian')),
    })
    o.default('dark', pane('black'), background)
    o.default('tentacles', pane('purple'), tentacles + [4])
    o.default('deep', item('sculk'), deep)

    o.add_reward('middle', 'showcase', 'SEQUENTAL', middle,
                 [(20, 1, 2), (12, 1), (6, 2), (3, 3), (2, 5), (1, 7), (1, 9), (1, 12)], delay=8)
    o.add_display_reel('left', left)
    o.add_display_reel('right', right)

    o.add_launch('squirt', cue(sound('entity.squid.squirt', 1, 0.6), 0))
    o.add_launch('ambience', cue(sound('block.conduit.ambient', 1, 0.6), 0))
    o.add_launch('squirt_2', cue(sound('entity.glow_squid.squirt', 0.8, 0.5), 8))
    add_move_cues(o, 'block.note_block.didgeridoo', 0.5, 1.0, 0.5)

    ultra = o.reveal('ultra', {'Broadcast': True}, [
        sound('entity.warden.sonic_boom', 0.6, 1), sound('entity.player.levelup', 1, 0.6)])
    ultra['boom'] = cue(sound('entity.warden.sonic_boom', 0.6, 1), 0)
    for i, spinner in enumerate(['flash', 'glow2', 'flash', 'glow']):
        ultra[f'lash_{i}'] = anim(spinner, 'SYNCRHONIZED', tentacles, [(1, 1)], i * 3)
    for d in range(1, 6):
        ring = [s for s in background if max(abs(s // 9), abs(s % 9 - 4)) == d]
        if ring:
            ultra[f'bloom_{d}'] = anim('crying', 'SYNCRHONIZED', ring, [(1, 1)], 10 + 2 * d)
    ultra['anchor'] = cue(sound('block.respawn_anchor.charge', 1, 0.6), 12)
    ultra['anchor_2'] = cue(sound('block.respawn_anchor.set_spawn', 1, 0.7), 20)
    ultra['halo'] = anim('glow2', 'SYNCRHONIZED', halo, [(1, 1)], 22)
    ultra['fanfare'] = cue(sound('entity.player.levelup', 1, 0.6), 22)

    normal = o.reveal('normal', None, [
        sound('entity.squid.squirt', 1, 0.5), sound('entity.player.levelup', 0.8, 0.8)])
    normal['squirt'] = cue(sound('entity.squid.squirt', 1, 0.5), 0)
    normal['retract_left'] = anim('dark', 'SEQUENTAL', list(reversed(left)), [(10, 1)], 0)
    normal['retract_right'] = anim('dark', 'SEQUENTAL', list(reversed(right)), [(10, 1)], 0)
    normal['retract_middle'] = anim('dark', 'SEQUENTAL', list(reversed(middle[:-1])), [(9, 1)], 0)
    normal['halo'] = anim('glow', 'SYNCRHONIZED', halo, [(1, 1)], 10)
    normal['chime'] = cue(sound('entity.player.levelup', 0.8, 0.8), 10)
    return o


# ---------------------------------------------------------------------------------------------------------
# 3. Summoning Rite
# ---------------------------------------------------------------------------------------------------------

def summoning_rite():
    o = Opening('summoning_rite', 6, '<dark_purple>✦</dark_purple> %crate_name% <dark_purple>✦</dark_purple>', 13, [
        '"Summoning Rite" crate opening for ExcellentCrates 6.6.1-catcraft.6 or newer. Put it in openings/inventory/.',
        'A ritual circle: 18 rewards sit on a ring round a crying-obsidian altar and the ring turns like a real',
        'wheel (LOOP mode, nothing new enters). As it slows, the runes round the edge light up one by one, the',
        'last as it stops; the prize stops under the watching eye (top centre of the ring).',
        'Normal win: a white wave runs round the runes, the altar burns with soul fire and the ring goes dark',
        'except the prize. Ultra: blackout, then a magenta portal swirls out from the altar (end portal, wither).',
        'The crate\'s ultras ride the ring but always stop on its far side.',
    ])
    ring = [10, 11, 12, 13, 14, 15, 16, 25, 34, 43, 42, 41, 40, 39, 38, 37, 28, 19]   # clockwise; 13 under the eye
    altar = [20, 21, 22, 23, 24, 29, 30, 31, 32, 33]
    runes = [5, 6, 7, 8, 17, 26, 35, 44, 53, 52, 51, 50, 49, 48, 47, 46, 45, 36, 27, 18, 9, 0, 1, 2, 3]
    eye = [4]

    o.spinners.update({
        'dark': single(pane('black')),
        'white': single(pane('white', glint=True)),
        'rune': single(pane('purple', glint=True)),
        'portal': single(pane('magenta', glint=True)),
        'soul_fire': single(pane('cyan', glint=True)),
        'crying': single(item('crying_obsidian')),
        'eye': single(item('ender_eye', glint=True)),
    })
    o.default('dark', pane('black'), runes + ring)
    o.default('altar', item('crying_obsidian'), altar)
    o.default('eye', item('ender_eye'), eye)

    o.add_reward('wheel', 'showcase', 'LOOP', ring,
                 [(1, 1), (27, 1), (9, 2), (5, 3), (3, 5), (2, 7), (1, 10), (1, 13)], delay=6)
    stop = o.reel_stop
    fill = o.reel_ticks[0]

    o.add_launch('ambience', cue(sound('ambient.basalt_deltas.mood', 1, 0.6), 0))
    o.add_launch('summon', cue(sound('entity.evoker.prepare_summon', 0.8, 0.7), fill))
    # Light the runes one by one from the fill to the stop, the last on the reel's final move.
    for i, (rune, tick) in enumerate(zip(runes, falling(fill + 4, stop, len(runes)))):
        tick = round(tick)
        o.add_launch(f'rune_{i}', anim('rune', 'INDEPENDENT', [rune], [(1, 1)], tick))
        o.add_launch(f'rune_{i}_sound', cue(sound('block.enchantment_table.use', 0.5, 0.6 + i * 0.04), tick))
    add_move_cues(o, 'block.amethyst_block.hit', 0.6, 1.6, 0.8)

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
    ultra['altar'] = anim('crying', 'SYNCRHONIZED', altar, [(1, 1)], done)
    ultra['runes'] = anim('rune', 'SYNCRHONIZED', runes, [(1, 1)], done)
    ultra['eye'] = anim('eye', 'SYNCRHONIZED', eye, [(1, 1)], done)
    ultra['fanfare'] = cue(sound('entity.player.levelup', 1, 0.6), done)

    # Normal: a white wave runs round the runes, soul fire on the altar, the ring goes dark but for the prize.
    normal = o.reveal('normal', None, [
        sound('entity.evoker.cast_spell', 1, 0.8), sound('entity.player.levelup', 0.8, 0.9)])
    normal['cast'] = cue(sound('entity.evoker.cast_spell', 1, 0.8), 0)
    # One rune at a time (two SEQUENTAL waves on the same slots would push each other's panes along).
    for i, rune in enumerate(runes):
        normal[f'wave_{i}'] = anim('white', 'INDEPENDENT', [rune], [(1, 1)], i)
        normal[f'relight_{i}'] = anim('rune', 'INDEPENDENT', [rune], [(1, 1)], i + 6)
    normal['soul_fire'] = anim('soul_fire', 'SYNCRHONIZED', altar, [(1, 1)], 2)
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
