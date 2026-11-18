<script setup lang="ts">
import SiteHeader from '@/components/SiteHeader.vue'
import SiteFooter from '@/components/SiteFooter.vue'
import PhoneFrame from '@/components/PhoneFrame.vue'
import WindowFrame from '@/components/WindowFrame.vue'
import ScreenshotShowcase from '@/components/ScreenshotShowcase.vue'

const releases = 'https://github.com/yuuki-kagura/adfree/releases/latest'
const source = 'https://github.com/yuuki-kagura/adfree'

const trust = [
  {
    icon: 'i-lucide-smartphone',
    title: 'On-device',
    body: 'Rules run on your phone, not in a cloud. A blocked name costs no radio time.',
  },
  {
    icon: 'i-lucide-eye-off',
    title: 'No telemetry',
    body: 'No analytics, no crash reporting, no account. The request log stays in SQLite.',
  },
  {
    icon: 'i-lucide-cpu',
    title: 'Rust core',
    body: 'One engine for Android and desktop, reached through a uniffi bridge.',
  },
  {
    icon: 'i-simple-icons-github',
    title: 'Source on GitHub',
    body: 'Every filter decision, every test and every release build is readable.',
  },
]

const layers = [
  {
    step: '01',
    icon: 'i-lucide-globe',
    title: 'DNS',
    body: 'Queries are answered inside the TUN device — NXDOMAIN or 0.0.0.0 — so the block happens before a connection exists and costs no radio time.',
  },
  {
    step: '02',
    icon: 'i-lucide-lock-keyhole',
    title: 'HTTPS',
    body: 'Optional, off by default: port 443 is terminated with a local CA, HTML responses are rewritten and scripts injected. Gated on the certificate being trusted.',
  },
  {
    step: '03',
    icon: 'i-lucide-wand-sparkles',
    title: 'In the page',
    body: 'Cosmetic rules, scriptlets and userscripts remove the box that held the ad, not just the request that filled it.',
  },
]

const features = [
  {
    icon: 'i-lucide-network',
    title: 'Full-tunnel VPN',
    body: 'TCP on every port is relayed transparently and non-DNS UDP goes through a userspace NAT. QUIC and DoT are dropped so apps fall back to filterable TCP.',
  },
  {
    icon: 'i-lucide-layers',
    title: 'AdGuard, EasyList and uBO rules',
    body: 'adblock-rust does the matching, plus the response-body modifiers the crate lacks: $$ and ##^ HTML filtering, $jsonprune, $hls, and $path/$url masks.',
  },
  {
    icon: 'i-lucide-layout-grid',
    title: 'Per-app control',
    body: 'Rules can be narrowed to an app with $app, and whole apps can be routed or excluded from the tunnel in the UI.',
  },
  {
    icon: 'i-lucide-eye-off',
    title: 'Tracking protection',
    body: 'One master switch over fourteen edits: header and cookie rewrites, browser-API guards, handshake splitting (anti-DPI) and IP masking. Three levels write twelve at once.',
  },
  {
    icon: 'i-lucide-code-2',
    title: 'Cosmetic filtering and userscripts',
    body: 'An injected page runtime with @require and a safe subset of the GM APIs, so a site can be fixed without a new app build.',
  },
  {
    icon: 'i-lucide-zap',
    title: 'Smart filtering',
    body: 'Opt-in: a host blocked seven times in ten seconds gets a one-minute pass, so a retry loop stops burning CPU. Off by default because the pass is real.',
  },
  {
    icon: 'i-lucide-building-2',
    title: 'Company breakdown',
    body: 'Statistics group blocked traffic by domain and label each with the company, service and category from AdGuard’s tracker dataset and Ghostery TrackerDB.',
  },
  {
    icon: 'i-lucide-flask-conical',
    title: 'Security Lab',
    body: 'Offline threat analysis with no network calls. When a security list blocks a domain, one notification opens the log filtered to it.',
  },
  {
    icon: 'i-lucide-bot',
    title: 'Automation',
    body: 'Tasker and friends can switch protection through an exported receiver — only after you turn the gate on, and only with that install’s token.',
  },
]

const platforms = [
  {
    icon: 'i-simple-icons-android',
    name: 'Android',
    body: 'The shipping shell: a full-tunnel VpnService, six Compose tabs, always-on reconnect and Tasker automation.',
    points: ['APK from the latest release', 'minSdk 24 · arm64, armv7, x86_64', 'No root'],
  },
  {
    icon: 'i-simple-icons-windows',
    name: 'Windows',
    body: 'A desktop window over the same core, DNS redirects installed per adapter, and a tray that keeps running when the window closes.',
    points: ['MSI for x64 and arm64', 'Per-adapter DNS redirect', 'Tray + auto-start'],
  },
  {
    icon: 'i-simple-icons-linux',
    name: 'Linux',
    body: 'A deb or an rpm with the whole UI on the host JVM. The CA goes into the distro’s own trust store, with your consent.',
    points: ['deb and rpm', 'Debian, Arch, Fedora stores', 'pkexec for the CA only'],
  },
]

const faq = [
  {
    label: 'Is AdFree a VPN?',
    icon: 'i-lucide-shield-question',
    content:
      'It uses Android’s VpnService as the tunnel, but there is no remote exit: packets are filtered and routed on the phone. Nothing is sent to a server we run, because there is no server.',
  },
  {
    label: 'Does it need root?',
    icon: 'i-lucide-key-round',
    content:
      'No. Android uses VpnService. On desktop the DNS redirects are installed with your consent and dropped before the listeners close — quitting AdFree, or a signal that kills it, takes the rules down with it.',
  },
  {
    label: 'Does HTTPS filtering require a certificate?',
    icon: 'i-lucide-badge-check',
    content:
      'Yes, and it is off until you install the local CA. When the system does not trust it, the engine relays instead of filtering rather than pretending to filter. The app says which store it found on this machine.',
  },
  {
    label: 'What leaves the device?',
    icon: 'i-lucide-cloud-off',
    content:
      'Nothing. No backend, no API key, no telemetry. Filter lists update from URLs or files you choose, the request log lives in SQLite, and the automation token is kept out of every backup.',
  },
  {
    label: 'How is it different from a DNS blocker?',
    icon: 'i-lucide-git-compare',
    content:
      'DNS is one layer. AdFree can also terminate TLS, rewrite HTML, run cosmetic rules and userscripts, apply per-app rules, and make the fourteen privacy edits a page or a network can observe.',
  },
  {
    label: 'Is it free?',
    icon: 'i-lucide-gift',
    content:
      'Yes. The source is on GitHub and every release is free to download — built by CI from the tag, not by a second build with its own idea of the tree.',
  },
]
</script>

<template>
  <SiteHeader />

  <main>
    <!-- ---------------------------------------------------------------- hero -->
    <section class="relative overflow-hidden">
      <div class="pointer-events-none absolute inset-0 grid-lines" />
      <div
        class="pointer-events-none absolute -top-48 left-1/2 h-[32rem] w-[64rem] -translate-x-1/2 rounded-full bg-primary/20 blur-[130px]"
      />
      <UContainer class="relative py-20 lg:py-28">
        <div class="grid items-center gap-16 lg:grid-cols-[1.02fr_1fr]">
          <div>
            <UBadge color="primary" variant="subtle" size="lg" class="rounded-full">
              On-device · Rust core · No telemetry
            </UBadge>
            <h1 class="mt-6 text-balance text-4xl font-semibold tracking-tight text-highlighted sm:text-5xl lg:text-6xl">
              The web,
              <span class="text-primary">without the ads.</span>
            </h1>
            <p class="mt-6 max-w-xl text-lg/8 text-muted">
              AdFree filters ads, trackers and annoyances on your own device. DNS, HTTPS and
              cosmetic rules, a per-app firewall and fourteen privacy edits — all behind one Rust
              core, with no backend and no telemetry.
            </p>
            <div class="mt-8 flex flex-wrap gap-3">
              <UButton
                size="lg"
                label="Download AdFree"
                trailing-icon="i-lucide-download"
                to="#download"
              />
              <UButton
                size="lg"
                color="neutral"
                variant="subtle"
                label="How it works"
                trailing-icon="i-lucide-arrow-right"
                to="#how"
              />
            </div>
            <div class="mt-8 flex flex-wrap items-center gap-x-6 gap-y-2 text-sm text-muted">
              <span class="flex items-center gap-2">
                <UIcon name="i-lucide-monitor-smartphone" class="size-4 text-primary" />
                Android, Windows, Linux
              </span>
              <span class="flex items-center gap-2">
                <UIcon name="i-lucide-server-off" class="size-4 text-primary" />
                Nothing uploaded
              </span>
            </div>
          </div>

          <div class="relative pb-16 sm:pb-20">
            <WindowFrame
              src="/shots/desktop-home.png"
              alt="AdFree on the desktop: Home with protection on"
              title="AdFree"
            />
            <div class="absolute -bottom-2 left-2 w-36 sm:-bottom-6 sm:left-0 sm:w-44">
              <PhoneFrame src="/shots/android-home.png" alt="AdFree on Android: Home" />
            </div>
          </div>
        </div>
      </UContainer>
    </section>

    <!-- --------------------------------------------------------------- trust -->
    <section class="border-y border-default bg-elevated/20">
      <UContainer>
        <dl class="grid gap-8 py-10 sm:grid-cols-2 lg:grid-cols-4">
          <div v-for="item in trust" :key="item.title" class="flex gap-4">
            <div class="flex size-10 shrink-0 items-center justify-center rounded-xl bg-primary/10 text-primary">
              <UIcon :name="item.icon" class="size-5" />
            </div>
            <div>
              <dt class="text-sm font-semibold text-highlighted">{{ item.title }}</dt>
              <dd class="mt-1 text-sm/6 text-muted">{{ item.body }}</dd>
            </div>
          </div>
        </dl>
      </UContainer>
    </section>

    <!-- ----------------------------------------------------------------- how -->
    <section id="how" class="py-20 sm:py-28">
      <UContainer>
        <div class="mx-auto max-w-2xl text-center">
          <p class="text-sm font-medium uppercase tracking-[0.18em] text-primary">How it works</p>
          <h2 class="mt-3 text-3xl font-semibold tracking-tight text-highlighted sm:text-4xl">
            Three layers, one switch each
          </h2>
          <p class="mt-4 text-base/7 text-muted">
            Cheap blocks first; the deep ones stay off until you turn them on. Nothing here needs a
            server of ours to make a decision.
          </p>
        </div>

        <div class="mt-12 grid gap-6 md:grid-cols-3">
          <div
            v-for="layer in layers"
            :key="layer.step"
            class="relative rounded-2xl border border-default bg-elevated/30 p-6"
          >
            <span class="text-xs font-semibold tracking-[0.2em] text-primary/70">{{ layer.step }}</span>
            <div class="mt-4 flex size-11 items-center justify-center rounded-xl bg-primary/10 text-primary">
              <UIcon :name="layer.icon" class="size-5" />
            </div>
            <h3 class="mt-4 text-lg font-semibold text-highlighted">{{ layer.title }}</h3>
            <p class="mt-2 text-sm/6 text-muted">{{ layer.body }}</p>
          </div>
        </div>
      </UContainer>
    </section>

    <!-- ------------------------------------------------------------ features -->
    <section id="features" class="border-t border-default py-20 sm:py-28">
      <UContainer>
        <div class="max-w-2xl">
          <p class="text-sm font-medium uppercase tracking-[0.18em] text-primary">Features</p>
          <h2 class="mt-3 text-3xl font-semibold tracking-tight text-highlighted sm:text-4xl">
            What the core actually does
          </h2>
          <p class="mt-4 text-base/7 text-muted">
            Not a remote DNS resolver with a switch. A filtering engine that understands what a
            page asked for, and what the app that asked is allowed to do.
          </p>
        </div>

        <div class="mt-12 grid gap-px overflow-hidden rounded-2xl border border-default bg-default sm:grid-cols-2 lg:grid-cols-3">
          <div
            v-for="feature in features"
            :key="feature.title"
            class="group bg-elevated/20 p-6 transition hover:bg-elevated/40"
          >
            <div class="flex size-10 items-center justify-center rounded-xl border border-primary/20 bg-primary/10 text-primary">
              <UIcon :name="feature.icon" class="size-5" />
            </div>
            <h3 class="mt-4 text-base font-semibold text-highlighted">{{ feature.title }}</h3>
            <p class="mt-2 text-sm/6 text-muted">{{ feature.body }}</p>
          </div>
        </div>
      </UContainer>
    </section>

    <!-- ------------------------------------------------------------- numbers -->
    <section class="border-t border-default bg-elevated/20 py-14">
      <UContainer>
        <div class="grid gap-8 text-center sm:grid-cols-4">
          <div>
            <p class="text-3xl font-semibold text-highlighted">0</p>
            <p class="mt-1 text-sm text-muted">servers of ours</p>
          </div>
          <div>
            <p class="text-3xl font-semibold text-highlighted">14</p>
            <p class="mt-1 text-sm text-muted">privacy edits</p>
          </div>
          <div>
            <p class="text-3xl font-semibold text-highlighted">3</p>
            <p class="mt-1 text-sm text-muted">protection levels</p>
          </div>
          <div>
            <p class="text-3xl font-semibold text-highlighted">1</p>
            <p class="mt-1 text-sm text-muted">Rust core</p>
          </div>
        </div>
      </UContainer>
    </section>

    <!-- --------------------------------------------------------------- proof -->
    <section id="proof" class="border-t border-default py-20 sm:py-28">
      <UContainer>
        <div class="mx-auto max-w-2xl text-center">
          <p class="text-sm font-medium uppercase tracking-[0.18em] text-primary">
            Third-party test
          </p>
          <h2 class="mt-3 text-3xl font-semibold tracking-tight text-highlighted sm:text-4xl">
            123 of 130 ads stopped, on someone else's page
          </h2>
          <p class="mt-4 text-base/7 text-muted">
            <a
              href="https://adblock.turtlecute.org/"
              target="_blank"
              rel="noopener"
              class="text-primary underline-offset-4 hover:underline"
              >adblock.turtlecute.org</a
            >
            loads 130 known ad and tracker requests and counts how many never arrive. AdFree stops
            123 of them — 95% — on the phone and on the desktop, with HTTPS filtering deliberately
            left off, so every one of those requests was refused by a name, not by a rewritten
            certificate.
          </p>
        </div>

        <div class="mt-12 grid items-center gap-10 lg:grid-cols-2">
          <div class="mx-auto w-full max-w-[19rem]">
            <PhoneFrame src="/shots/android-test.png" alt="AdFree scoring 95% on adblock.turtlecute.org in Chrome for Android" />
          </div>
          <div class="space-y-6">
            <div class="rounded-2xl border border-default bg-elevated/30 p-2 shadow-2xl shadow-black/40">
              <img
                src="/shots/desktop-test.png"
                alt="AdFree scoring 95% on adblock.turtlecute.org in a desktop browser"
                class="w-full rounded-xl"
              />
            </div>
            <div class="grid grid-cols-3 gap-4 text-center">
              <div class="rounded-xl border border-default bg-elevated/20 p-4">
                <p class="text-2xl font-semibold text-primary">95%</p>
                <p class="mt-1 text-xs text-muted">score</p>
              </div>
              <div class="rounded-xl border border-default bg-elevated/20 p-4">
                <p class="text-2xl font-semibold text-highlighted">123</p>
                <p class="mt-1 text-xs text-muted">requests blocked</p>
              </div>
              <div class="rounded-xl border border-default bg-elevated/20 p-4">
                <p class="text-2xl font-semibold text-highlighted">0</p>
                <p class="mt-1 text-xs text-muted">certificates rewritten</p>
              </div>
            </div>
            <div class="flex flex-wrap gap-3">
              <UButton
                to="https://adblock.turtlecute.org/"
                target="_blank"
                rel="noopener"
                icon="i-lucide-external-link"
                >Run the test yourself</UButton
              >
              <UButton to="#download" color="neutral" variant="outline">Get the build</UButton>
            </div>
          </div>
        </div>
      </UContainer>
    </section>

    <ScreenshotShowcase />

    <!-- ------------------------------------------------------------ platforms -->
    <section id="platforms" class="border-t border-default py-20 sm:py-28">
      <UContainer>
        <div class="mx-auto max-w-2xl text-center">
          <p class="text-sm font-medium uppercase tracking-[0.18em] text-primary">Platforms</p>
          <h2 class="mt-3 text-3xl font-semibold tracking-tight text-highlighted sm:text-4xl">
            Pick the shape you need
          </h2>
          <p class="mt-4 text-base/7 text-muted">
            Every build starts from the same tag and the same core. Android is the shipping phone
            shell; the desktop packages run the whole UI on the host.
          </p>
        </div>

        <div class="mt-12 grid gap-6 md:grid-cols-3">
          <div
            v-for="platform in platforms"
            :key="platform.name"
            class="flex flex-col rounded-2xl border border-default bg-elevated/20 p-6"
          >
            <div class="flex items-center gap-3">
              <div class="flex size-10 items-center justify-center rounded-xl bg-primary/10 text-primary">
                <UIcon :name="platform.icon" class="size-5" />
              </div>
              <h3 class="text-lg font-semibold text-highlighted">{{ platform.name }}</h3>
            </div>
            <p class="mt-4 text-sm/6 text-muted">{{ platform.body }}</p>
            <ul class="mt-5 space-y-2 text-sm text-muted">
              <li v-for="point in platform.points" :key="point" class="flex items-start gap-2">
                <UIcon name="i-lucide-check" class="mt-0.5 size-4 shrink-0 text-primary" />
                {{ point }}
              </li>
            </ul>
            <UButton
              class="mt-6 w-full justify-center"
              color="neutral"
              variant="subtle"
              label="Get the build"
              trailing-icon="i-lucide-arrow-up-right"
              :to="releases"
              target="_blank"
            />
          </div>
        </div>
      </UContainer>
    </section>

    <!-- ------------------------------------------------------------- download -->
    <section id="download" class="border-t border-default py-20 sm:py-28">
      <UContainer>
        <div class="relative overflow-hidden rounded-3xl border border-primary/20 bg-gradient-to-br from-primary/15 via-elevated/40 to-elevated/10 p-8 sm:p-12">
          <div class="pointer-events-none absolute -right-24 -top-24 size-72 rounded-full bg-primary/20 blur-[90px]" />
          <div class="relative grid items-center gap-10 lg:grid-cols-[1.2fr_1fr]">
            <div>
              <h2 class="text-3xl font-semibold tracking-tight text-highlighted sm:text-4xl">
                Install it, then forget it
              </h2>
              <p class="mt-4 max-w-xl text-base/7 text-muted">
                Pick a platform and take the build from the latest release. Every artefact is the
                one CI produced from the tag — Android APK, Windows MSI, Linux deb and rpm.
              </p>
              <div class="mt-8 flex flex-wrap gap-3">
                <UButton
                  size="lg"
                  icon="i-simple-icons-github"
                  label="Latest release"
                  :to="releases"
                  target="_blank"
                />
                <UButton
                  size="lg"
                  color="neutral"
                  variant="subtle"
                  icon="i-lucide-book-open"
                  label="Build from source"
                  :to="source"
                  target="_blank"
                />
              </div>
              <p class="mt-6 text-xs/5 text-dimmed">
                No account, no sign-up, no telemetry to switch off. If the build is not for you,
                the source is the whole story.
              </p>
            </div>

            <div class="grid gap-3 rounded-2xl border border-default bg-default/60 p-5 text-sm">
              <div class="flex items-center justify-between gap-4">
                <span class="flex items-center gap-2 text-muted">
                  <UIcon name="i-simple-icons-android" class="size-4" />
                  Android
                </span>
                <span class="text-highlighted">APK</span>
              </div>
              <div class="h-px bg-default" />
              <div class="flex items-center justify-between gap-4">
                <span class="flex items-center gap-2 text-muted">
                  <UIcon name="i-simple-icons-windows" class="size-4" />
                  Windows
                </span>
                <span class="text-highlighted">MSI</span>
              </div>
              <div class="h-px bg-default" />
              <div class="flex items-center justify-between gap-4">
                <span class="flex items-center gap-2 text-muted">
                  <UIcon name="i-simple-icons-linux" class="size-4" />
                  Linux
                </span>
                <span class="text-highlighted">deb · rpm</span>
              </div>
            </div>
          </div>
        </div>
      </UContainer>
    </section>

    <!-- ------------------------------------------------------------------ faq -->
    <section id="faq" class="border-t border-default py-20 sm:py-28">
      <UContainer>
        <div class="grid gap-12 lg:grid-cols-[0.8fr_1.2fr]">
          <div>
            <p class="text-sm font-medium uppercase tracking-[0.18em] text-primary">FAQ</p>
            <h2 class="mt-3 text-3xl font-semibold tracking-tight text-highlighted sm:text-4xl">
              The questions that matter
            </h2>
            <p class="mt-4 text-base/7 text-muted">
              Short answers here; DESIGN.md has the long ones, down to which socket error bit
              decides what.
            </p>
            <UButton
              class="mt-6"
              color="neutral"
              variant="subtle"
              label="Read the design notes"
              trailing-icon="i-lucide-arrow-up-right"
              to="https://github.com/yuuki-kagura/adfree/blob/main/DESIGN.md"
              target="_blank"
            />
          </div>

          <UAccordion :items="faq" color="neutral" variant="subtle" />
        </div>
      </UContainer>
    </section>
  </main>

  <SiteFooter />
</template>
