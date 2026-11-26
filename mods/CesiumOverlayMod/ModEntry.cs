// Material You floating window for CesiumLoader on Android: a draggable rounded blob that snaps
// to the nearer side edge, expands into a vertical bar with six nav slots, and opens the matching
// page panel on the bar's side. Geometry, easing and timings mirror the HTML prototype
// (see docs/overlay-material-design.md).
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using CesiumAndroid;
using CesiumLoader.SDK.Mods;
using CesiumLoader.SDK.Manifests;
using FairyGUI;
using FairyGUI.Utils;
using UnityEngine;

[assembly: ModManifest("Cesium Android FloatingWindow", "1.1.0", "TenkinMori/囚鱼玖", "为CesiumLoader安卓端定制的悬浮窗口", Permissions = ModPermission.ReadGameState | ModPermission.UI | ModPermission.FileWrite | ModPermission.SpeedHack)]
namespace CesiumOverlayMod
{
    public static class ModEntry
    {
        public static void Main() { ModBase.Run(new OverlayController(), tag: "CesiumOverlay"); }
    }

    public sealed partial class OverlayController : ModBase
    {
        private enum ShellMode { Idle, Drag, Expanded, Busy }

        private GComponent root, blob, panel, body, backdrop, navRoot, panelHead;
        private OverlaySkin blobSkin, panelSkin;
        private GLoader logo, panelIcon;
        private GTextField panelTitle, notice;
        private GComponent[] navItems;
        private GGraph[] navPills;
        private GLoader[] navIcons;
        private readonly OverlayMotion motion = new OverlayMotion();
        private readonly Dictionary<string, NTexture> textures = new Dictionary<string, NTexture>(StringComparer.Ordinal);

        private float width, height, uiScale;
        private float S, R, Wbar, Hbar, Rbar, Wpanel, PGAP, MARGIN, PAD, LOGO_B, GAP1, NAV, NAV_GAP;
        private float vW, vH, vX, vCY, rTL, rTR, rBL, rBR, scale, logoSize, logoCX, logoCY, navAlpha, panelAlpha, backdropAlpha, lift;
        private ShellMode mode = ShellMode.Idle;
        private string edge = "left";
        private bool docked = true;
        private int page;
        private int openModPage = -1;
        private string openMod;
        private float nextBuild, nextRefresh;
        private string contentKey;
        private string lastNotice;
        private float noticeAt;
        private bool renderPending;
        /// <summary>Bypasses the finger-down guard for renders that must land immediately.</summary>
        private bool forceRender;
        /// <summary>Row the blob sat on before expanding; collapsing puts it back there.</summary>
        private float restoreCY;
        /// <summary>Smoothed blob alpha so the idle fade does not pop.</summary>
        private float blobAlpha = 1f;
        /// <summary>Last time the user touched or expanded the shell, driving the idle fade.</summary>
        private float lastTouched;

        /// <summary>How long a notice stays on screen after the last change, in seconds.</summary>
        private const float NoticeSeconds = 4f;

        private float Px(float design) => design * uiScale;
        /// <summary>Panel text size: base design size, times the user's font scale.</summary>
        private int Font(float design) => Mathf.RoundToInt(design * uiScale * Mathf.Clamp(Options.FontScale, .8f, 1.4f));

        /// <summary>
        /// Phones must land in exactly the same place on both edges, so the bar is centred there.
        /// Tablets keep the dragged position, which is why the two edges can differ.
        /// </summary>
        private bool Compact => Mathf.Min(width, height) <= 1200f;

        public override string Name => "Cesium Android FloatingWindow";
        public override string Version => "1.1.0";

        public override void OnLoad() { SpeedPanel.BeginSession(); }

        public override void OnInitialize()
        {
            VirtualKeyboard.Install();
            SpeedPanel.BeginSession();
            AndroidApi.RegisterMod("CesiumOverlayMod", "工具箱信息");
        }

        public override void OnUpdate()
        {
            VirtualKeyboard.Instance?.Tick();
            float now = Time.realtimeSinceStartup;

            TickNotice(now);

            if (now >= nextBuild)
            {
                nextBuild = now + 1f;
                bool changed = PollOptions();
                if (OverlayTheme.Poll()) { changed = true; }
                BuiltinActions.Refresh();
                if (root == null || root.isDisposed || root.parent != GRoot.inst
                    || width != GRoot.inst.width || height != GRoot.inst.height)
                {
                    // Swapping the whole tree while a finger is down destroys the button under it,
                    // so the tap never completes. Retry shortly after the finger lifts instead.
                    if (FingerDown) nextBuild = now + .1f;
                    else Build();
                }
                else if (changed)
                {
                    // Settings and theme changes never alter the geometry, so repainting the panel
                    // is enough. Rebuilding the whole tree made every label visibly jump.
                    PaintChrome();
                    contentKey = null;
                    Render();
                }
            }

            motion.Update(now);
            TickLongPress();
            ApplyLayout();

            // Deferred work keeps the tree alive until the finger that asked for it lifts.
            // Rebuilding from inside a click handler destroyed the very button being dispatched
            // and froze the panel for a frame or two, so page controls only raise a flag now.
            if (renderPending && !FingerDown)
            {
                renderPending = false;
                Render();
            }

            // Poll while the shell is open at all, not just on a detail page: page rows carry live
            // state (a shortcut that latched, a mod switched on elsewhere), and without this the
            // row kept its old colours until the user navigated away and back. Render() diffs a
            // content key first, so an unchanged page costs one string comparison.
            if (mode == ShellMode.Expanded && now >= nextRefresh)
            {
                nextRefresh = now + .25f;
                Render();
            }
        }

        public override void OnLateUpdate() { LocalSpeed.Tick(); }

        /// <summary>True while any pointer is down, used to postpone work that would rebuild the tree.</summary>
        private bool FingerDown
        {
            get
            {
                try { return Stage.inst != null && Stage.inst.touchCount > 0; }
                catch (Exception) { return false; }
            }
        }

        /// <summary>
        /// Notices are transient: they used to sit in the panel footer forever, so once the speed
        /// page said "游戏倍率 2×（Unity）" that line kept showing on every other page.
        /// </summary>
        private void TickNotice(float now)
        {
            string live = AndroidApi.Notice ?? string.Empty;
            if (!string.Equals(live, lastNotice, StringComparison.Ordinal))
            {
                lastNotice = live;
                noticeAt = now;
            }
            if (notice == null || notice.isDisposed) return;
            bool fresh = !string.IsNullOrEmpty(live) && now - noticeAt < NoticeSeconds;
            string shown = fresh ? live : string.Empty;
            if (!string.Equals(notice.text, shown, StringComparison.Ordinal)) notice.text = shown;
        }

        public override void OnUnload()
        {
            VirtualKeyboard.Uninstall();
            LocalSpeed.Restore();
            HudVisibility.Restore();
            motion.Clear();
            if (root != null && !root.isDisposed) root.Dispose();
            blobSkin?.Dispose(); blobSkin = null;
            panelSkin?.Dispose(); panelSkin = null;
            foreach (var texture in textures.Values) texture.Dispose();
            textures.Clear();
        }

        // ---- build ---------------------------------------------------------------------
        private void Build()
        {
            bool fresh = root == null || root.isDisposed;
            bool hadState = !fresh;
            width = GRoot.inst.width; height = GRoot.inst.height;
            uiScale = Mathf.Max(.4f, Mathf.Min(1.5f, Mathf.Min(width, height) / 600f));

            S = Mathf.Max(Px(56), Mathf.Min(Px(88), Mathf.Min(width, height) * .09f));
            R = S * .30f;
            PAD = Px(16); LOGO_B = Px(56); GAP1 = Px(18); NAV = Px(56); NAV_GAP = Px(8);
            Wbar = Mathf.Max(S, Px(96));
            PGAP = Px(10);
            // Set before Hbar is clamped below: the clamp used last frame's margin, which let the
            // bar overflow the screen edge for one build.
            MARGIN = Px(12);
            // One row per page plus the logo/padding; six entries need visibly more room than four.
            Hbar = PAD + LOGO_B + GAP1 + OverlayPresentation.Pages.Length * NAV
                + (OverlayPresentation.Pages.Length - 1) * NAV_GAP + PAD;
            // Tablet has spare height, so the bar keeps some slack instead of hugging the content.
            if (!Compact) Hbar += Px(72);
            Hbar = Mathf.Min(Hbar, height - 2 * MARGIN);
            Rbar = Px(28);
            Wpanel = Mathf.Min(Px(400), width - Wbar - Px(36));

            // Remembered position wins over the default, so the second open lands where the user
            // left it; the window is rebuilt on every resolution/theme change and must not drift.
            float keepCY = hadState ? vCY
                : Options.Center >= 0f ? Mathf.Clamp(Options.Center, 0f, 1f) * height
                : height * .18f;
            bool hadExpanded = hadState && (mode == ShellMode.Expanded || mode == ShellMode.Busy);

            motion.Clear();
            if (!fresh) { root.Dispose(); }
            // Disposing the root takes the page body with it; clear the handle so Render() never
            // touches a disposed component (FairyGUI throws from inside set_visible on one).
            body = null;
            textures.Clear();

            root = new GComponent { name = "CesiumAndroid_RepositoryOverlay", sortingOrder = 32740, opaque = false };
            root.SetSize(width, height);
            GRoot.inst.AddChild(root);

            backdrop = new GComponent { name = "overlay.backdrop", opaque = true };
            backdrop.SetSize(width, height);
            backdrop.onClick.Add((EventContext context) => { context.StopPropagation(); Collapse(); });
            root.AddChild(backdrop);

            panel = new GComponent { name = "overlay.panel", opaque = true };
            root.AddChild(panel);
            panelSkin = new OverlaySkin(panel, "overlay.panel.skin");

            panelHead = new GComponent { name = "overlay.panel.head", opaque = false };
            panel.AddChild(panelHead);

            blob = new GComponent { name = "CesiumAndroid_Avatar", opaque = true };
            root.AddChild(blob);

            // Anti-aliased rounded rectangle: one seamless CPU-baked texture, re-baked only when
            // the box outgrows it by 15% and once more at native resolution when it comes to rest.
            blobSkin = new OverlaySkin(blob, "overlay.blob.skin");

            logo = new GLoader { name = "overlay.logo", touchable = false, fill = FillType.ScaleFree };
            var logoTex = Texture("logo", "overlay.png");
            if (logoTex != null) logo.texture = logoTex; else logo.url = null;
            blob.AddChild(logo);

            navRoot = new GComponent { name = "overlay.nav", opaque = false };
            blob.AddChild(navRoot);
            navItems = new GComponent[OverlayPresentation.Pages.Length];
            navPills = new GGraph[OverlayPresentation.Pages.Length];
            navIcons = new GLoader[OverlayPresentation.Pages.Length];
            int pages = OverlayPresentation.Pages.Length;
            float navBlock = pages * NAV + (pages - 1) * NAV_GAP;
            // Centre the stack in whatever height the bar ended up with; on phones the logo above it
            // wins the space, on tablets the extra slack is split evenly.
            float navTop = Mathf.Max(PAD + LOGO_B + GAP1, (Hbar - navBlock) / 2f);
            for (int i = 0; i < pages; i++)
            {
                int index = i;
                var page_ = OverlayPresentation.Pages[i];
                var item = new GComponent { name = "overlay.nav." + page_.Key, opaque = true };
                item.SetSize(NAV, NAV);
                item.SetXY((Wbar - NAV) / 2f, navTop + i * (NAV + NAV_GAP));
                navRoot.AddChild(item);

                var pill = new GGraph { touchable = false };
                pill.DrawRoundRect(NAV, NAV, OverlayTheme.PrimaryContainer, new[] { NAV / 2f, NAV / 2f, NAV / 2f, NAV / 2f });
                item.AddChild(pill);

                var icon = new GLoader { touchable = false, fill = FillType.ScaleFree };
                var iconTex = Texture(page_.Icon, "icons/" + page_.Icon + ".png");
                if (iconTex != null) icon.texture = iconTex;
                icon.SetSize(Px(24), Px(24));
                icon.SetXY((NAV - Px(24)) / 2f, (NAV - Px(24)) / 2f);
                item.AddChild(icon);

                item.onTouchBegin.Add((EventContext context) => context.StopPropagation());
                item.onClick.Add((EventContext context) => { context.StopPropagation(); SelectPage(index); });

                navItems[i] = item; navPills[i] = pill; navIcons[i] = icon;
            }

            notice = new GTextField
            {
                name = "overlay.notice", touchable = false, text = string.Empty,
                autoSize = AutoSizeType.Shrink, verticalAlign = VertAlignType.Middle,
            };
            panel.AddChild(notice);

            wireBlobInput();
            contentKey = null;
            openMod = null;
            openModPage = -1;

            if (hadState && hadExpanded && edge == "right")
            {
                vW = Wbar; vH = Hbar;
            }
            else if (hadState && hadExpanded)
            {
                vW = Wbar; vH = Hbar;
            }
            else
            {
                vW = S; vH = S;
            }
            vCY = Mathf.Clamp(keepCY, MARGIN + vH / 2f, Mathf.Max(MARGIN + vH / 2f, height - MARGIN - vH / 2f));
            vX = edge == "right" ? width - vW : 0f;
            scale = 1f; lift = 0f;
            logoSize = vW == S ? S * .58f : LOGO_B;
            logoCX = vW == S ? S / 2f : Wbar / 2f;
            logoCY = vW == S ? S / 2f : PAD + LOGO_B / 2f;
            navAlpha = vW == S ? 0f : 1f;
            panelAlpha = vW == S ? 0f : 1f;
            backdropAlpha = vW == S ? 0f : 1f;
            mode = vW == S ? ShellMode.Idle : ShellMode.Expanded;
            ApplyCorners(vW == S ? R : Rbar, true);
            PaintChrome();
            Render();
        }

        // ---- geometry / paint ----------------------------------------------------------
        private void ApplyCorners(float target, bool instant)
        {
            bool left = edge == "left";
            float tl = docked && left ? 0f : target;
            float bl = docked && left ? 0f : target;
            float tr = docked && !left ? 0f : target;
            float br = docked && !left ? 0f : target;
            if (instant) { rTL = tl; rTR = tr; rBL = bl; rBR = br; return; }
            motion.To(rTL, tl, .18f, OverlayEase.SineInOut, k => rTL = k);
            motion.To(rTR, tr, .18f, OverlayEase.SineInOut, k => rTR = k);
            motion.To(rBL, bl, .18f, OverlayEase.SineInOut, k => rBL = k);
            motion.To(rBR, br, .18f, OverlayEase.SineInOut, k => rBR = k);
        }

        private void ApplyLayout()
        {
            if (root == null || root.isDisposed) return;
            float y = Mathf.Clamp(vCY - vH / 2f, MARGIN, Mathf.Max(MARGIN, height - vH - MARGIN));
            float panelW = Mathf.Min(Wpanel * Mathf.Clamp(Options.Width, .65f, 1.6f),
                Mathf.Max(Px(160), width - Wbar - PGAP - MARGIN));
            float panelH = Mathf.Min(vH * Mathf.Clamp(Options.Height, .65f, 1.6f), height - 2 * MARGIN);
            float panelX = edge == "left" ? vX + vW + PGAP : vX - PGAP - panelW;

            backdrop.SetSize(width, height);
            backdrop.alpha = backdropAlpha * .999f;
            backdrop.visible = backdropAlpha > .01f;
            backdrop.touchable = backdropAlpha > .01f;

            blob.SetXY(vX, y);
            // The blob starts life as a zero-sized component; without an explicit size FairyGUI
            // never hit-tests it, so taps fell through to the backdrop and nothing expanded.
            blob.SetSize(Mathf.Max(1f, vW), Mathf.Max(1f, vH));
            blob.SetScale(scale, scale);
            // The idle blob dims itself after a few quiet seconds so it stops covering the board.
            // Tapping it, dragging it or expanding the bar wakes it back up immediately.
            float idleWait = Options.IdleSeconds;
            float idleAlpha = Mathf.Clamp(Options.IdleOpacity, .15f, 1f);
            bool asleep = mode == ShellMode.Idle && idleWait > 0f
                && Time.realtimeSinceStartup - lastTouched >= idleWait;
            float wanted = asleep ? idleAlpha : 1f;
            blobAlpha = Mathf.MoveTowards(blobAlpha, wanted, Time.unscaledDeltaTime * 2.6f);
            // Keep a sliver of alpha: a fully transparent GObject stops receiving touches.
            blob.alpha = Mathf.Max(.02f, blobAlpha);
            PaintSurface(blobSkin, Mathf.Max(1f, vW), Mathf.Max(1f, vH),
                rTL, rTR, rBL, rBR, OverlayTheme.SurfaceContainerHigh);

            logo.SetSize(logoSize, logoSize);
            logo.SetXY(logoCX - logoSize / 2f, logoCY - logoSize / 2f);

            navRoot.alpha = navAlpha;
            navRoot.visible = navAlpha > .01f;
            for (int i = 0; i < navItems.Length; i++)
            {
                if (navItems[i] == null) continue;
                navItems[i].touchable = navAlpha > .5f;
                bool selected = i == page;
                navPills[i].visible = selected;
                navIcons[i].color = selected ? OverlayTheme.OnPrimaryContainer : OverlayTheme.OnSurfaceVariant;
            }

            panel.alpha = panelAlpha * Options.Opacity;
            panel.visible = panelAlpha > .01f;
            panel.touchable = panelAlpha > .5f;
            panel.SetSize(panelW, panelH);
            panel.SetXY(panelX + (1f - panelAlpha) * Px(24) * (edge == "left" ? 1 : -1), y);
            float panelRadius = Px(24);
            PaintSurface(panelSkin, Mathf.Max(1f, panelW), Mathf.Max(1f, panelH),
                panelRadius, panelRadius, panelRadius, panelRadius, OverlayTheme.SurfaceContainer);

            panelHead.SetSize(panelW, Px(56));
            panelHead.SetXY(0, 0);

            notice.SetXY(Px(20), panelH - Px(34));
            notice.SetSize(Mathf.Max(1, panelW - Px(40)), Px(26));
            // The text itself is owned by TickNotice so stale messages can expire.

            if (body != null) body.SetXY(Px(16), Px(64));
        }

        /// <summary>
        /// Paints one rounded surface through its skin: a flat fill, no stroke.
        ///
        /// A baked outline became thick and blurry once it was resampled, and the user asked for
        /// no border at all, so elevation is carried purely by the tonal surface colour. The skin
        /// bakes one seamless texture at roughly the drawn size, so this call is cheap enough to
        /// run on every frame of an animation; the settled flag merely asks for the exact bake.
        /// </summary>
        private void PaintSurface(OverlaySkin skin, float w, float h, float tl, float tr, float bl, float br, Color fill)
        {
            if (skin == null) return;
            skin.Paint(Mathf.Max(1f, w), Mathf.Max(1f, h), Mathf.Max(0f, tl), Mathf.Max(0f, tr),
                Mathf.Max(0f, bl), Mathf.Max(0f, br), fill, motion.Idle);
        }

        private void PaintChrome()
        {
            if (panelHead == null) return;
            if (panelIcon == null || panelIcon.isDisposed)
            {
                panelIcon = new GLoader { touchable = false, fill = FillType.ScaleFree };
                panelHead.AddChild(panelIcon);
            }
            else if (panelIcon.parent != panelHead)
            {
                panelHead.AddChild(panelIcon);
            }
            panelIcon.SetSize(Px(24), Px(24));
            panelIcon.SetXY(Px(20), Px(16));
            panelIcon.color = OverlayTheme.Primary;
            string iconName = OverlayPresentation.Pages[Mathf.Clamp(page, 0, OverlayPresentation.Pages.Length - 1)].Icon;
            var iconTex = Texture(iconName, "icons/" + iconName + ".png");
            if (iconTex != null) panelIcon.texture = iconTex;

            if (panelTitle == null || panelTitle.isDisposed)
            {
                panelTitle = new GTextField
                {
                    touchable = false, autoSize = AutoSizeType.Shrink, verticalAlign = VertAlignType.Middle,
                };
                panelHead.AddChild(panelTitle);
            }
            else if (panelTitle.parent != panelHead)
            {
                panelHead.AddChild(panelTitle);
            }
            var format = panelTitle.textFormat;
            format.size = Font(20);
            format.color = OverlayTheme.OnSurface;
            panelTitle.textFormat = format;
            panelTitle.text = OverlayPresentation.Pages[Mathf.Clamp(page, 0, OverlayPresentation.Pages.Length - 1)].Title;
            panelTitle.SetXY(Px(52), Px(10));
            panelTitle.SetSize(Px(200), Px(36));
        }

        // ---- interaction ---------------------------------------------------------------
        private Vector2 dragStart;
        private Vector2 dragOrigin;
        private bool dragging;
        private int dragTouch = -1;

        private void wireBlobInput()
        {
            blob.onTouchBegin.Add((EventContext context) =>
            {
                if (mode == ShellMode.Expanded || mode == ShellMode.Busy) return;
                dragTouch = context.inputEvent.touchId;
                lastTouched = Time.realtimeSinceStartup;
                dragStart = GRoot.inst.GlobalToLocal(context.inputEvent.position);
                dragOrigin = new Vector2(vX, vCY - vH / 2f);
                dragging = false;
                context.CaptureTouch();
            });

            blob.onTouchMove.Add((EventContext context) =>
            {
                if (dragTouch < 0 || context.inputEvent.touchId != dragTouch) return;
                if (mode == ShellMode.Expanded || mode == ShellMode.Busy) return;
                Vector2 current = GRoot.inst.GlobalToLocal(context.inputEvent.position);
                float dx = current.x - dragStart.x;
                float dy = current.y - dragStart.y;
                if (!dragging)
                {
                    if (Mathf.Sqrt(dx * dx + dy * dy) < Px(6)) return;
                    dragging = true;
                    mode = ShellMode.Drag;
                    motion.To(scale, 1.04f, .12f, OverlayEase.CubicOut, k => scale = k);
                    motion.To(lift, 1f, .12f, OverlayEase.CubicOut, k => lift = k);
                    docked = false;
                    ApplyCorners(R, false);
                }
                vX = Mathf.Clamp(dragOrigin.x + dx, 0f, Mathf.Max(0f, width - vW));
                vCY = Mathf.Clamp(dragOrigin.y + dy + vH / 2f, MARGIN + vH / 2f, Mathf.Max(MARGIN + vH / 2f, height - MARGIN - vH / 2f));
                context.StopPropagation();
            });

            blob.onTouchEnd.Add((EventContext context) =>
            {
                if (dragTouch < 0 || context.inputEvent.touchId != dragTouch) return;
                dragTouch = -1;
                if (mode == ShellMode.Drag) EndDrag();
                else if (mode == ShellMode.Idle && !dragging) Expand();
                dragging = false;
            });
        }

        private void EndDrag()
        {
            mode = ShellMode.Busy;
            motion.To(scale, 1f, .16f, OverlayEase.CubicOut, k => scale = k);
            motion.To(lift, 0f, .16f, OverlayEase.CubicOut, k => lift = k);
            edge = vX + vW / 2f < width / 2f ? "left" : "right";
            float targetX = edge == "left" ? 0f : width - vW;
            docked = false;
            ApplyCorners(R, false);
            // The idle blob may sit anywhere; only the expanded bar is pinned to one row on phones.
            motion.To(vX, targetX, .24f, OverlayEase.QuartOut, k => vX = k, 0f, () =>
            {
                mode = ShellMode.Idle;
                docked = true;
                ApplyCorners(R, false);
                SaveCenter(vCY);
            });
        }

        private void Expand()
        {
            if (mode != ShellMode.Idle) return;
            mode = ShellMode.Busy;
            lastTouched = Time.realtimeSinceStartup;
            // The bar is taller than the blob on every screen, so it has to move to make room.
            // Remember the row the user pressed and centre the bar instead: centring on tablets too
            // keeps the remember/restore pair symmetrical, and the blob returns to [restoreCY] on
            // collapse, which is the row it was opened from.
            restoreCY = vCY;
            float cyTarget = height / 2f;
            // Long, decelerating moves read as one continuous stretch instead of three separate
            // properties arriving at different times; the height trails the width on purpose.
            motion.To(vW, Wbar, .34f, OverlayEase.QuartOut, k => { vW = k; vX = edge == "left" ? 0f : width - k; });
            motion.To(vH, Hbar, .40f, OverlayEase.QuartOut, k => vH = k);
            motion.To(vCY, cyTarget, .40f, OverlayEase.QuartOut, k => vCY = k);
            ApplyCorners(Rbar, false);
            motion.To(logoSize, LOGO_B, .40f, OverlayEase.QuartOut, k => logoSize = k);
            motion.To(logoCX, Wbar / 2f, .40f, OverlayEase.QuartOut, k => logoCX = k);
            motion.To(logoCY, PAD + LOGO_B / 2f, .40f, OverlayEase.QuartOut, k => logoCY = k);
            motion.To(backdropAlpha, 1f, .30f, OverlayEase.CubicOut, k => backdropAlpha = k);
            motion.To(navAlpha, 1f, .24f, OverlayEase.CubicOut, k => navAlpha = k, .16f);
            motion.To(panelAlpha, 1f, .30f, OverlayEase.QuartOut, k => panelAlpha = k, .22f, () =>
            {
                if (mode == ShellMode.Busy) { mode = ShellMode.Expanded; contentKey = null; Render(); }
            });
        }

        private void Collapse()
        {
            if (mode != ShellMode.Expanded && mode != ShellMode.Busy) return;
            mode = ShellMode.Busy;
            openMod = null; openModPage = -1;
            // Expand() centres the bar, so collapsing has to undo that move or the blob ends up
            // somewhere the user never put it. Restore the remembered row (a rebuild can start from
            // the expanded state, in which case [restoreCY] is unset and the current row is used).
            float cyTarget = Mathf.Clamp(restoreCY > 0f ? restoreCY : vCY,
                MARGIN + S / 2f, Mathf.Max(MARGIN + S / 2f, height - MARGIN - S / 2f));
            motion.To(panelAlpha, 0f, .12f, OverlayEase.CubicIn, k => panelAlpha = k);
            motion.To(backdropAlpha, 0f, .24f, OverlayEase.CubicIn, k => backdropAlpha = k);
            motion.To(navAlpha, 0f, .10f, OverlayEase.Linear, k => navAlpha = k);
            motion.To(vW, S, .26f, OverlayEase.CubicIn, k => { vW = k; vX = edge == "left" ? 0f : width - k; });
            motion.To(vH, S, .26f, OverlayEase.CubicIn, k => vH = k);
            motion.To(vCY, cyTarget, .26f, OverlayEase.CubicIn, k => vCY = k);
            ApplyCorners(R, false);
            motion.To(logoSize, S * .58f, .26f, OverlayEase.CubicIn, k => logoSize = k);
            motion.To(logoCX, S / 2f, .26f, OverlayEase.CubicIn, k => logoCX = k);
            motion.To(logoCY, S / 2f, .26f, OverlayEase.CubicIn, k => logoCY = k, 0f, () =>
            {
                // Collapsing counts as a fresh interaction, otherwise the blob would dim mid-animation.
                lastTouched = Time.realtimeSinceStartup;
                // Collapsing only puts the shell away; the page the user was on has to survive,
                // otherwise every reopen drops them back on 功能 page.
                if (mode == ShellMode.Busy) { mode = ShellMode.Idle; PaintChrome(); }
            });
        }

        private void SelectPage(int index)
        {
            if (page == index && openModPage < 0) return;
            page = index;
            openMod = null; openModPage = -1;
            AndroidApi.ShowNotice(string.Empty);
            PaintChrome();
            // The old page used to dip to 55% opacity and back, which read as the whole panel
            // shivering on every tab change. The body is rebuilt anyway, so just swap it.
            contentKey = null;
            Render();
        }

        // ---- shared helpers ------------------------------------------------------------
        internal static void BlockGestures(EventContext context) { context.StopPropagation(); }

        private NTexture Texture(string key, string relative)
        {
            if (textures.TryGetValue(key, out NTexture cached)) return cached;
            try
            {
                string path = AndroidApi.GetLoaderPath("mods", "CesiumOverlayMod", relative.Replace('/', Path.DirectorySeparatorChar));
                if (!File.Exists(path)) return null;
                if (!PngDecoder.TryDecode(File.ReadAllBytes(path), out int w, out int h, out byte[] rgba)) return null;
                // Unity uploads raw texture data bottom-up; the source PNG is top-down.
                var flipped = new byte[rgba.Length];
                int stride = w * 4;
                for (int y = 0; y < h; y++)
                    Buffer.BlockCopy(rgba, y * stride, flipped, (h - 1 - y) * stride, stride);
                var texture = new Texture2D(w, h, TextureFormat.RGBA32, false);
                texture.LoadRawTextureData(flipped);
                texture.Apply(false, false);
                var result = new NTexture(texture);
                textures[key] = result;
                return result;
            }
            catch (Exception error)
            {
                Log.Warn("overlay texture failed: " + relative + " " + error.Message);
                return null;
            }
        }

        internal static GGraph RoundRect(GComponent parent, float w, float h, Color color, float radius, bool border = false)
        {
            var graph = new GGraph { touchable = false };
            graph.DrawRoundRect(w, h, border ? OverlayTheme.Outline : color, new[] { radius, radius, radius, radius });
            parent.AddChild(graph);
            if (border)
            {
                var fill = new GGraph { touchable = false };
                fill.DrawRoundRect(w - 2, h - 2, color, new[] { radius - 1, radius - 1, radius - 1, radius - 1 });
                fill.SetXY(1, 1); parent.AddChild(fill);
            }
            return graph;
        }

        private GTextField Text(GComponent parent, string value, int size, float x, float y, float w, float h, Color? color = null, bool center = false)
        {
            var text = new GTextField
            {
                touchable = false, text = value, autoSize = AutoSizeType.Shrink,
                verticalAlign = VertAlignType.Middle, align = center ? AlignType.Center : AlignType.Left,
            };
            var format = text.textFormat; format.size = size; format.color = color ?? OverlayTheme.OnSurface; text.textFormat = format;
            text.SetSize(w, h); text.SetXY(x, y); parent.AddChild(text); return text;
        }

        private GComponent Button(GComponent parent, string label, string name, float x, float y, float w, float h, Action action, Color? background = null, Color? foreground = null)
        {
            var button = new GComponent { name = name, opaque = true };
            button.SetXY(x, y); button.SetSize(w, h); parent.AddChild(button);
            RoundRect(button, w, h, background ?? OverlayTheme.SurfaceContainerHigh, Px(14));
            if (!string.IsNullOrEmpty(label)) Text(button, label, Font(15), Px(10), 0, w - Px(20), h, foreground, true);
            button.onTouchBegin.Add(() => { if (!button.isDisposed) button.alpha = .72f; });
            button.onTouchEnd.Add(() => { if (!button.isDisposed) button.alpha = 1f; });
            button.onClick.Add((EventContext context) => { context.StopPropagation(); action(); });
            return button;
        }
    }

    /// <summary>Native inertial vertical scrolling, clipped to the panel; no full-screen gesture catcher.</summary>
    internal sealed class OverlayScrollView : GComponent
    {
        internal OverlayScrollView(float w, float h)
        {
            SetSize(w, h);
            // Opaque makes the whole viewport hit-testable, so a drag that starts on empty space
            // between rows still reaches the scroll pane. Without it only the rows themselves
            // could start a scroll, which is why the list felt stuck unless you grabbed a button.
            opaque = true;
            byte[] data = { (byte)ScrollType.Vertical, (byte)ScrollBarDisplayType.Hidden,
                0, 0, 0, 0x90, 0, 0xff, 0xfe, 0xff, 0xfe, 0xff, 0xfe, 0xff, 0xfe };
            SetupScroll(new ByteBuffer(data));
            scrollPane.mouseWheelEnabled = true;
        }
    }
}
