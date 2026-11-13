// SPDX-License-Identifier: GPL-2.0
/*
 * Hyper-V synthetic timer (stimer0, direct mode) as the per-CPU clockevent device, for
 * guests that QEMU runs on the Windows Hypervisor Platform with -accel whpx,hyperv=on.
 *
 * On some Snapdragon X hosts (X1-26-100), Hyper-V's emulation of the architected
 * virtual timer leaves a vCPU stalled in the hypervisor between timer ticks, so a guest
 * that ticks from it runs about 100 times slower than it should. Hyper-V's own synthetic
 * timers do not have that problem; the WSL2 kernel uses them for the same reason.
 *
 * The arch timer still provides the clocksource and sched_clock; only the tick moves.
 * Enabled with "whpx_stimer" on the kernel command line, which the emulator adds only
 * when it also enables the Hyper-V enlightenments. Without it, nothing here runs.
 */

#define pr_fmt(fmt) "whpx_stimer: " fmt

#include <linux/arm-smccc.h>
#include <linux/clockchips.h>
#include <linux/cpuhotplug.h>
#include <linux/init.h>
#include <linux/interrupt.h>
#include <linux/irq.h>
#include <linux/irqdomain.h>
#include <linux/of.h>
#include <linux/percpu.h>
#include <linux/printk.h>
#include <linux/version.h>

#define WHPX_HV_FUNC_ID		ARM_SMCCC_CALL_VAL(ARM_SMCCC_STD_CALL, ARM_SMCCC_SMC_64, \
						   ARM_SMCCC_OWNER_VENDOR_HYP, 1)
#define HVCALL_GET_VP_REGISTERS	0x0050ULL
#define HVCALL_SET_VP_REGISTERS	0x0051ULL
#define HV_HYPERCALL_FAST	BIT_ULL(16)
#define HV_HYPERCALL_REP_1	BIT_ULL(32)
#define HV_PARTITION_SELF	(~0ULL)
#define HV_VP_SELF		0xfffffffeULL
#define HV_STATUS_MASK		0xffffULL

#define HV_REG_GUEST_OS_ID	0x00090002
#define HV_REG_TIME_REF_COUNT	0x00090004
#define HV_REG_STIMER0_CONFIG	0x000B0000
#define HV_REG_STIMER0_COUNT	0x000B0001

#define HV_CLOCK_HZ		10000000	/* reference time runs in 100 ns units */
#define WHPX_STIMER_INTID	31		/* a PPI; the GIC has it edge-triggered */

/* Hyper-V stimer config: Enable | AutoEnable | DirectMode, interrupt in bits 4-11. */
#define STIMER_CONFIG		(BIT_ULL(0) | BIT_ULL(3) | BIT_ULL(12) | \
				 ((u64)WHPX_STIMER_INTID << 4))

static bool whpx_stimer_requested __ro_after_init;
static int whpx_stimer_irq __ro_after_init;
static DEFINE_PER_CPU(struct clock_event_device, whpx_stimer_ce);

static int __init whpx_stimer_setup(char *arg)
{
	whpx_stimer_requested = true;
	return 0;
}
early_param("whpx_stimer", whpx_stimer_setup);

static u64 hv_set_reg(u32 reg, u64 val)
{
	struct arm_smccc_res res;

	arm_smccc_1_1_hvc(WHPX_HV_FUNC_ID,
			  HVCALL_SET_VP_REGISTERS | HV_HYPERCALL_FAST | HV_HYPERCALL_REP_1,
			  HV_PARTITION_SELF, HV_VP_SELF, reg, 0, val, 0, &res);
	return res.a0 & HV_STATUS_MASK;
}

static u64 hv_get_reg(u32 reg, u64 *val)
{
	struct arm_smccc_1_2_regs args = {
		.a0 = WHPX_HV_FUNC_ID,
		.a1 = HVCALL_GET_VP_REGISTERS | HV_HYPERCALL_FAST | HV_HYPERCALL_REP_1,
		.a2 = HV_PARTITION_SELF,
		.a3 = HV_VP_SELF,
		.a4 = reg,
	};
	struct arm_smccc_1_2_regs res;

	arm_smccc_1_2_hvc(&args, &res);
	*val = res.a6;
	return res.a0 & HV_STATUS_MASK;
}

static u64 hv_guest_os_id(void)
{
	/* Same format as Linux's hv_generate_guest_id(): open-source Linux, kernel version. */
	return (0x8100ULL << 48) | ((u64)LINUX_VERSION_CODE << 16);
}

static int whpx_stimer_next_event(unsigned long delta, struct clock_event_device *ce)
{
	u64 now = 0;

	hv_get_reg(HV_REG_TIME_REF_COUNT, &now);
	hv_set_reg(HV_REG_STIMER0_COUNT, now + delta);
	return 0;
}

static int whpx_stimer_shutdown(struct clock_event_device *ce)
{
	hv_set_reg(HV_REG_STIMER0_COUNT, 0);
	hv_set_reg(HV_REG_STIMER0_CONFIG, 0);
	return 0;
}

static int whpx_stimer_oneshot(struct clock_event_device *ce)
{
	hv_set_reg(HV_REG_STIMER0_CONFIG, STIMER_CONFIG);
	return 0;
}

static irqreturn_t whpx_stimer_isr(int irq, void *dev_id)
{
	struct clock_event_device *ce = dev_id;

	if (ce->event_handler)
		ce->event_handler(ce);
	return IRQ_HANDLED;
}

static int whpx_stimer_starting_cpu(unsigned int cpu)
{
	struct clock_event_device *ce = per_cpu_ptr(&whpx_stimer_ce, cpu);

	hv_set_reg(HV_REG_GUEST_OS_ID, hv_guest_os_id());

	ce->name = "Hyper-V stimer (whpx)";
	ce->features = CLOCK_EVT_FEAT_ONESHOT;
	ce->rating = 1000;	/* above the arch timer's 450, so it takes over the tick */
	ce->cpumask = cpumask_of(cpu);
	ce->irq = whpx_stimer_irq;
	ce->set_state_shutdown = whpx_stimer_shutdown;
	ce->set_state_oneshot = whpx_stimer_oneshot;
	ce->tick_resume = whpx_stimer_oneshot;
	ce->set_next_event = whpx_stimer_next_event;

	enable_percpu_irq(whpx_stimer_irq, IRQ_TYPE_NONE);
	clockevents_config_and_register(ce, HV_CLOCK_HZ, 1, 0x7fffffff);
	return 0;
}

static int whpx_stimer_dying_cpu(unsigned int cpu)
{
	whpx_stimer_shutdown(this_cpu_ptr(&whpx_stimer_ce));
	disable_percpu_irq(whpx_stimer_irq);
	return 0;
}

/* Called from time_init() after the arch timer is set up, before interrupts are enabled. */
void __init whpx_stimer_init(void)
{
	struct irq_fwspec fwspec = {};
	struct device_node *gic;
	u64 status, ref = 0;
	int ret;

	if (!whpx_stimer_requested)
		return;

	status = hv_set_reg(HV_REG_GUEST_OS_ID, hv_guest_os_id());
	status |= hv_get_reg(HV_REG_TIME_REF_COUNT, &ref);
	if (status || !ref) {
		pr_err("Hyper-V synthetic timers unavailable (status %#llx); using the arch timer\n",
		       status);
		return;
	}

	gic = of_find_compatible_node(NULL, NULL, "arm,gic-v3");
	if (!gic) {
		pr_err("no GICv3 node; using the arch timer\n");
		return;
	}
	fwspec.fwnode = of_node_to_fwnode(gic);
	fwspec.param_count = 3;
	fwspec.param[0] = 1;				/* GIC_PPI */
	fwspec.param[1] = WHPX_STIMER_INTID - 16;
	fwspec.param[2] = IRQ_TYPE_EDGE_RISING;
	whpx_stimer_irq = irq_create_fwspec_mapping(&fwspec);
	of_node_put(gic);
	if (whpx_stimer_irq <= 0) {
		pr_err("cannot map PPI %d; using the arch timer\n", WHPX_STIMER_INTID);
		return;
	}

	ret = request_percpu_irq(whpx_stimer_irq, whpx_stimer_isr, "Hyper-V stimer",
				 &whpx_stimer_ce);
	if (ret) {
		pr_err("cannot request IRQ %d: %d; using the arch timer\n", whpx_stimer_irq, ret);
		return;
	}

	/* This state is unused on arm64 (x86's Hyper-V timer); it runs after the arch timer's. */
	ret = cpuhp_setup_state(CPUHP_AP_HYPERV_TIMER_STARTING,
				"clockevents/whpx_stimer:starting",
				whpx_stimer_starting_cpu, whpx_stimer_dying_cpu);
	if (ret) {
		pr_err("cpuhp setup failed: %d; using the arch timer\n", ret);
		free_percpu_irq(whpx_stimer_irq, &whpx_stimer_ce);
		return;
	}

	pr_info("ticking from the Hyper-V synthetic timer (PPI %d, reference time %llu)\n",
		WHPX_STIMER_INTID, ref);
}
