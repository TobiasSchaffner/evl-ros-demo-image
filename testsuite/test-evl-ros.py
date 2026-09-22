#!/usr/bin/env python3
#
# System test: boot the image under QEMU and check that the EVL core is up,
# that ros-evl-demo attaches a real-time thread to it, and that the ROS 2
# side of the demo publishes samples and reacts to commands.
#
# The QEMU invocation follows xenomai-images/start-qemu.sh.
#
# SPDX-License-Identifier: MIT
#

import argparse
import os
import re
import sys

import pexpect

# OSC/CSI noise: Ubuntu's bash ships shell integration that wraps every
# prompt and command in escape sequences.
ESCAPES = re.compile(
    r'\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)'
    r'|\x1bP[^\x1b]*\x1b\\'
    r'|\x1b\[[0-9;?]*[ -/]*[@-~]'
    r'|\x1b[=>]'
    r'|[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]'
)


# user@host:dir# left over from the previous command
PROMPT = re.compile(r'^\S+@\S+:[^#$]*[#$]\s*')


def clean(text):
    return ESCAPES.sub('', text).replace('\r', '')


# Per-machine boot recipe: how the image is deployed and how qemu reaches it.
# The amd64 invocation follows xenomai-images/start-qemu.sh; the arm64 one uses
# the "virt" machine rather than emulating a Pi, so it exercises the EVL core
# and the demo rather than the Raspberry Pi boot chain.
MACHINES = {
    'qemu-amd64': dict(
        qemu='qemu-system-x86_64', disk='.ext4', kernel='-vmlinuz',
        machine=['-machine', 'q35'],
        disk_args=lambda d: ['-drive', f'file={d},discard=unmap,if=none,'
                                      f'id=disk,format=raw,snapshot=on',
                             '-device', 'ide-hd,drive=disk'],
        append='root=/dev/sda rw console=ttyS0',
        net=['-device', 'virtio-net-pci,netdev=net'],
        kvm=True,
    ),
    'rpi4': dict(
        # wic is a whole disk, so the rootfs is the second partition
        qemu='qemu-system-aarch64', disk='.wic', kernel='-vmlinux',
        machine=['-machine', 'virt', '-cpu', 'cortex-a57'],
        disk_args=lambda d: ['-drive', f'file={d},if=none,id=disk,'
                                      f'format=raw,snapshot=on',
                             '-device', 'virtio-blk-device,drive=disk'],
        append='root=/dev/vda2 rw console=ttyAMA0',
        net=['-device', 'virtio-net-device,netdev=net'],
        kvm=False,  # emulated on an x86 host, so no acceleration
    ),
}


def qemu_cmdline(args):
    m = MACHINES[args.machine]
    prefix = os.path.join(
        args.build_dir, 'tmp/deploy/images', args.machine,
        f'{args.image}-{args.distro}-{args.machine}',
    )
    disk = prefix + m['disk']
    kernel, initrd = prefix + m['kernel'], prefix + '-initrd.img'
    for f in (disk, kernel, initrd):
        if not os.path.exists(f):
            sys.exit(f'missing build artifact: {f}\nbuild the image first')

    cmd = [m['qemu'], *m['machine'], '-smp', '4', '-m', '2G', '-nographic',
           *m['disk_args'](disk),
           '-kernel', kernel,
           '-initrd', initrd,
           '-append', m['append'],
           '-serial', 'mon:stdio',
           '-netdev', 'user,id=net', *m['net']]
    if m['kvm'] and os.access('/dev/kvm', os.R_OK | os.W_OK):
        cmd += ['-enable-kvm', '-cpu', 'host']
    return cmd


class Console:
    """Shell on the guest's serial console."""

    def __init__(self, child):
        self.child = child

    def run(self, cmd, timeout=60):
        """Run a command, return (exit status, output)."""
        self.child.sendline(f'{cmd}; echo "rc=$?"')
        self.child.expect(r'rc=(\d+)', timeout=timeout)
        rc = int(self.child.match.group(1))
        lines = [l for l in clean(self.child.before).splitlines() if l.strip()]
        if lines:
            lines[0] = PROMPT.sub('', lines[0])
            if not lines[0].strip() or cmd.split(';')[0][:24] in lines[0]:
                lines.pop(0)
        return rc, '\n'.join(lines).strip()


def check(name, ok, detail=''):
    print(f'{"PASS" if ok else "FAIL"}: {name}' + (f'  [{detail}]' if detail else ''))
    return ok


def run_checks(con, ros_distro):
    results = []

    # The EVL core creates its control device once it has started.
    rc, _ = con.run('test -c /dev/evl/control')
    _, release = con.run('uname -r')
    results.append(check('EVL core is up', rc == 0, f'kernel {release}'))

    # Start the demo detached, keep its output for the checks below.
    con.run(f'. /opt/ros/{ros_distro}/setup.bash', timeout=120)
    con.run('(setsid ros-evl-demo > /tmp/demo.log 2>&1 &)')
    rc, _ = con.run(
        '(for i in $(seq 60); do '
        'grep -q "ROS 2 node ready" /tmp/demo.log && exit 0; sleep 1; '
        'done; exit 1)', timeout=120)
    results.append(check('ros-evl-demo came up', rc == 0))

    rc, _ = con.run('grep -q "RT thread running at 1kHz" /tmp/demo.log')
    results.append(check('RT thread attached to the EVL core', rc == 0))

    # The core knows about the thread the demo created.
    _, out = con.run('evl ps')
    results.append(check('thread visible in "evl ps"', 'rt-demo' in out,
                         out.replace('\n', ' ')[:70]))

    # RT -> ROS 2: samples arrive on the topic.
    rc, out = con.run('timeout 60 ros2 topic echo /rt/sensor --once', timeout=90)
    results.append(check('/rt/sensor publishes samples',
                         rc == 0 and re.search(r'data:\s*-?\d', out) is not None,
                         out.replace('\n', ' ')[:70]))

    # ROS 2 -> RT: a command changes the amplitude the RT thread uses.
    con.run('timeout 30 ros2 topic pub --once /rt/command '
            'std_msgs/msg/Float64 "{data: 2.5}"', timeout=60)
    rc, _ = con.run(
        '(for i in $(seq 15); do '
        'grep -q "Amplitude set to 2.500" /tmp/demo.log && exit 0; sleep 1; '
        'done; exit 1)', timeout=60)
    results.append(check('/rt/command reaches the RT thread', rc == 0))

    if not all(results):
        _, log = con.run('cat /tmp/demo.log')
        print('--- /tmp/demo.log ---\n' + log + '\n---------------------')
        _, out = con.run('evl ps -l; ls /sys/devices/virtual/evl/thread 2>&1')
        print('--- evl state ---\n' + out + '\n-----------------')

    con.run('pkill ros-evl-demo || true')
    return results


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--build-dir', default='build')
    p.add_argument('--machine', default='qemu-amd64',
                   choices=sorted(MACHINES))
    p.add_argument('--distro', default='ros-xenomai')
    p.add_argument('--image', default='ros-xenomai-image')
    p.add_argument('--ros-distro', default='lyrical')
    p.add_argument('--boot-timeout', type=int, default=0,
                   help='default: 300s accelerated, 1800s emulated')
    p.add_argument('-v', '--verbose', action='store_true',
                   help='mirror the serial console to stdout')
    args = p.parse_args()
    if not args.boot_timeout:
        args.boot_timeout = 300 if MACHINES[args.machine]['kvm'] else 1800

    cmd = qemu_cmdline(args)
    print(' '.join(cmd) + '\n')
    child = pexpect.spawn(cmd[0], cmd[1:], timeout=args.boot_timeout,
                          encoding='utf-8', codec_errors='replace')
    if args.verbose:
        child.logfile_read = sys.stdout

    results = []
    try:
        child.expect(r'[\w.-]+ login:', timeout=args.boot_timeout)
        child.sendline('root')
        child.expect('Password:')
        child.sendline('root')
        child.expect(r'[#$] ', timeout=60)
        con = Console(child)
        con.run("stty -echo; export PROMPT_COMMAND=''; unset PS0")
        results = run_checks(con, args.ros_distro)
    except pexpect.TIMEOUT:
        print('FAIL: timed out waiting for the guest\n--- last output ---')
        print(child.before)
        results.append(False)
    except pexpect.EOF:
        print('FAIL: qemu exited unexpectedly')
        results.append(False)
    finally:
        if child.isalive():
            child.sendline('poweroff')
            try:
                child.expect(pexpect.EOF, timeout=60)
            except (pexpect.TIMEOUT, pexpect.EOF):
                pass
        child.terminate(force=True)

    print(f'\n{sum(results)}/{len(results)} checks passed')
    return 0 if results and all(results) else 1


if __name__ == '__main__':
    sys.exit(main())
