# DeltaBox UI redesign (Dark Premium + Purple Neon + Glass)

UI-only change. No VPN core / config / test logic was modified.

Changed files
- app/src/main/res/layout/layout_main.xml  (dock: cat button left, glass info panel right)
- app/src/main/res/layout/layout_appbar.xml, layout_app_list.xml, layout_apps.xml (popup theme)
- app/src/main/res/layout/layout_profile_list.xml (bottom padding for new dock)
- app/src/main/res/values/colors.xml, values-night/colors.xml (palette)
- app/src/main/res/values/themes.xml (glass popup menu, rounded cards, tab indicator)
- app/src/main/res/values/strings.xml, values-fa/strings.xml (4-5 labels)
- app/src/main/res/menu/add_profile_menu.xml (icons for the test-servers menu)
- app/src/main/res/drawable/ic_service_cat.xml + ic_service_{stopped,connecting,connected,stopping}.xml (cat logo + eye animation)
- new: drawable/bg_delta_dock, bg_delta_popup, bg_delta_chip, delta_status_dot, ic_delta_arrow_{up,down}; color/delta_cat_button_bg
- app/src/main/java/.../widget/StatsBar.kt (now a glass card; same public API)
- app/src/main/java/.../ui/MainActivity.kt (1 line: show the dock when the server list is shown)
- app/src/main/java/.../ui/ConfigurationFragment.kt (1 statement: show icons in the overflow menu)

Eye state: ServiceButton.changeState() <- MainActivity.changeState() <- SagerConnection.stateChanged()/onServiceConnected()
(real service state from the VPN service, not the click).
