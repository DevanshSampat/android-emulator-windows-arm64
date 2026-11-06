// gas_stubs.S - goldfish address-space device emulation at the syscall boundary.
//
// gfx_autopatch.py copies this code into the tail padding of an ELF's R-X
// PT_LOAD (the cave) and retargets only these call sites:
//   bl __open_2/open   with x0 == "/dev/goldfish_address_space"  -> gas_open
//   bl ioctl           with w1 == GOLDFISH_ADDRESS_SPACE_IOCTL_* -> gas_ioctl
//
// Both stubs try the REAL device first, so a patched binary behaves exactly
// like stock wherever /dev/goldfish_address_space exists. Emulation applies
// only when the device is missing (Windows ARM64 WHPX):
//
//   open()  -> memfd ("goldfish_address_space"), 1 TiB sparse. Each open()
//              gets a private address space, like the kernel driver.
//   ioctl() -> emulated only when the real ioctl returns -ENOTTY *and* the
//              fd is a memfd (F_GET_SEALS succeeds).
//
// The ioctl uapi has been stable since Android 10:
//   0xc018470a ALLOCATE_BLOCK   {u64 size; u64 offset; u64 phys_addr}
//   0xc008470b DEALLOCATE_BLOCK u64 offset
//   0xc028470c PING             {u64 offset, size, metadata; ...}
//   0xc010470d CLAIM_SHARED     {u64 offset, size}
//   0xc008470e UNCLAIM_SHARED   u64 offset
//
// Block layout: blocks are carved from the memfd by a bump cursor (the
// memfd's file position; lseek(SEEK_CUR) is atomic under f_pos_lock) that
// starts at 64 KiB, so offsets are never 0 and a PING Allocate can tell the
// shared-slots form (offset == 0, we allocate) from the classic form (already
// allocated by ALLOCATE_BLOCK). Blocks are 64 KiB aligned.
// DEALLOCATE is a no-op: memory returns when the last fd/mapping goes away.
// The allocator gives every buffer its own device fd (it travels in the
// gralloc handle), so in practice memory is reclaimed per buffer.
//
// Only syscalls that sandboxed graphics clients already use are issued:
// openat, memfd_create, ftruncate, ioctl, fcntl, lseek, socket, connect,
// close. (pwrite64/pread64/fallocate are blocked by e.g. media.swcodec's
// seccomp policy, which killed the process with SIGSYS.)
//
// AAPCS64 leaf code: clobbers only x0-x3, x8-x17 and flags; never x18
// (shadow call stack) or callee-saved registers; uses <=16 bytes of stack.

    .text
    .p2align 2
    .global gas_open, gas_ioctl, pipe_open, rc_update

// int pipe_open(int strcmp_refcount /* w0 */)
// Replaces the `bl open_verbose_vsock` in qemu_pipe_open_ns(). Entered with
// w0 = strcmp(name, "refcount"):
//   "refcount" -> AF_UNIX to "@whpx_refs". A buffer lives exactly as long as this
//                 fd (and its dups in other processes), so gfx_forwarder sees EOF
//                 when the buffer dies and tells the host. Falls back to a memfd:
//                 allocation must not fail because the forwarder is not up yet.
//   otherwise  -> AF_UNIX to "@whpx_pipe" (gfx_forwarder).
// Self-contained: only raw syscalls, no relocations, so it can be copied as
// [pipe_open, gas_open) alone, or [gas_open, end) alone, into small caves.
pipe_open:
    mov     w13, w0
    mov     x0, #1                      // AF_UNIX
    movz    x1, #1                      // SOCK_STREAM
    movk    x1, #8, lsl #16             // | SOCK_CLOEXEC (0x80000)
    mov     x2, #0
    mov     x8, #198                    // socket
    svc     #0
    tbnz    x0, #63, 7f
    mov     x9, x0
    movz    w10, #0x0001                // sun_family = AF_UNIX
    movk    w10, #0x7700, lsl #16       // '\0' 'w'
    movz    w11, #0x7068                // "hp"
    movk    w11, #0x5f78, lsl #16       // "x_"
    movz    w12, #0x6970                // "pi"
    movk    w12, #0x6570, lsl #16       // "pe"
    cbnz    w13, 2f
    movz    w12, #0x6572                // "re"
    movk    w12, #0x7366, lsl #16       // "fs"
2:  sub     sp, sp, #16
    stp     w10, w11, [sp]
    str     w12, [sp, #8]
    mov     x0, x9
    mov     x1, sp
    mov     x2, #12                     // 2 + strlen("\0whpx_pipe")
    mov     x8, #203                    // connect
    svc     #0
    add     sp, sp, #16
    tbz     x0, #63, 8f
    mov     x10, x0                     // connect failed: close it
    mov     x0, x9
    mov     x8, #57                     // close
    svc     #0
    mov     x0, x10                     // real pipes report -errno
7:  cbnz    w13, 9f
    str     xzr, [sp, #-16]!
    mov     x0, sp                      // name = ""
    mov     x1, #0
    mov     x8, #279                    // memfd_create
    svc     #0
    add     sp, sp, #16
9:  ret
8:  mov     x0, x9
    ret

    .equ GAS_SPAN, 0x10000000000        // 1 TiB sparse backing per open()
    .equ HDR, 0x10000                   // 64 KiB header / alignment

// int gas_open(const char *path, int flags[, ...])
gas_open:
    mov     x9, x0
    mov     w10, w1
    mov     x0, #-100                   // AT_FDCWD
    mov     x1, x9
    mov     w2, w10
    mov     x3, #0
    mov     x8, #56                     // openat: real device if present
    svc     #0
    tbz     x0, #63, 9f
    adr     x0, .Lname
    mov     x1, #0
    mov     x8, #279                    // memfd_create
    svc     #0
    tbnz    x0, #63, 8f
    mov     x9, x0
    mov     x1, #GAS_SPAN
    mov     x8, #46                     // ftruncate
    svc     #0
    mov     x0, x9
    mov     x1, #HDR                    // cursor starts at 64 KiB: offsets != 0
    mov     x2, #0                      // SEEK_SET
    mov     x8, #62                     // lseek
    svc     #0
    mov     x0, x9
9:  ret
8:  mov     w0, #-1
    ret

// int gas_ioctl(int fd, unsigned long req, void *arg)
gas_ioctl:
    mov     x9, x0                      // fd
    mov     x10, x1                     // request
    mov     x11, x2                     // arg
    mov     x8, #29                     // ioctl: real device first
    svc     #0
    cmn     x0, #25                     // -ENOTTY?
    b.eq    .Lemu
    tbz     x0, #63, 1f
    mov     w0, #-1
1:  ret

.Lemu:
    mov     x0, x9
    mov     x1, #1034                   // F_GET_SEALS: only memfds succeed
    mov     x8, #25                     // fcntl
    svc     #0
    tbnz    x0, #63, .Lfail
    ubfx    w12, w10, #8, #8
    cmp     w12, #0x47                  // 'G'
    b.ne    .Lfail
    and     w12, w10, #0xff
    cmp     w12, #10
    b.lo    .Lfail
    b.eq    .Lalloc
    cmp     w12, #11
    b.eq    .Lfree
    cmp     w12, #12
    b.eq    .Lping
    cmp     w12, #14
    b.hi    .Lfail
.Lok:                                   // 13 CLAIM_SHARED / 14 UNCLAIM_SHARED
    mov     w0, #0
    ret
.Lfail:
    mov     w0, #-1
    ret

.Lalloc:
    ldr     x13, [x11]                  // size
    mov     x15, x30
    bl      .Lbump
    mov     x30, x15
    tbnz    x14, #63, .Lfail
    str     x13, [x11]                  // size (rounded)
    str     x14, [x11, #8]              // offset
    str     x14, [x11, #16]             // phys_addr: unique, phys - offset == 0
    b       .Lok

.Lfree:                                 // DEALLOCATE: no-op (see header)
    b       .Lok

.Lping:
    ldr     x12, [x11, #16]             // metadata = HostMemoryAllocatorCommand
    cmp     x12, #1                     // Allocate (Free: nothing to do)
    b.ne    .Lping_ok
    ldr     x12, [x11]
    cbnz    x12, .Lping_ok              // already allocated via ALLOCATE_BLOCK
    ldr     x13, [x11, #8]              // shared-slots: allocate here
    mov     x15, x30
    bl      .Lbump
    mov     x30, x15
    tbnz    x14, #63, .Lfail
    str     x14, [x11]                  // offset
    str     x13, [x11, #8]              // size
.Lping_ok:
    str     xzr, [x11, #16]             // metadata = 0: host says OK
    b       .Lok

// in: x9 fd, x13 size  out: x14 block offset (<0 on error), x13 rounded size
.Lbump:
    add     x13, x13, #HDR
    sub     x13, x13, #1
    and     x13, x13, #0xffffffffffff0000
    mov     x0, x9
    mov     x1, x13
    mov     x2, #1                      // SEEK_CUR
    mov     x8, #62                     // lseek
    svc     #0
    tbnz    x0, #63, 2f
    mov     x12, #GAS_SPAN
    cmp     x0, x12
    b.hi    3f
    sub     x14, x0, x13                // block offset = new cursor - size
    ret
3:  mov     x0, #-12                    // -ENOMEM
2:  mov     x14, x0
    ret

// int rc_update(self, cb, x, y, w, h, format, type, [sp] pixels, [sp+8] size)
// Replaces the mapper's rcUpdateColorBufferDMA call. rcUpdateColorBuffer streams the
// pixels but derives their length from w, h and format, which is wrong for YUV frames:
// RGBA format with fewer than 4 bytes per pixel. It would promise bytes the buffer does
// not hold, the write stops short and host and guest wait for each other forever. So
// send `size` single bytes, with the real dimensions in x and y for the host to restore.
rc_update:
    ldr     w9, [sp, #8]                // size
    umull   x10, w4, w5
    lsl     x10, x10, #2
    mov     w11, #0x1908                // GL_RGBA
    cmp     w6, w11
    b.ne    1f
    cmp     x9, x10
    b.hs    1f
    mov     w2, w4
    mov     w3, w5
    mov     w4, w9
    mov     w5, #1
    mov     w6, #0x1909                 // GL_LUMINANCE: one byte per pixel
1:  ldr     x16, [x0, #200]             // slot 25: rcUpdateColorBuffer
    br      x16

.Lname:
    .asciz  "goldfish_address_space"
    .p2align 2
