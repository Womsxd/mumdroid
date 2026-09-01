package dev.woms.mumdroid.data

/**
 * A third-party open source project used by mumdroid, together with the license
 * under which it is distributed.
 *
 * @param name          human-readable project name
 * @param copyright     copyright / attribution line (may be empty)
 * @param license       SPDX-style license identifier, e.g. "Apache License 2.0"
 * @param url           upstream project URL
 * @param descriptionRes string resource for the short description of how it is
 *   used in this app (localised)
 */
data class OpenSourceProject(
    val name: String,
    val copyright: String,
    val license: String,
    val url: String,
    val descriptionRes: Int,
)

/**
 * Static list of the open source components used by mumdroid.
 *
 * Kept as a curated, human-maintained list so that the attribution screen stays
 * accurate without pulling in an extra runtime dependency.
 */
object OpenSourceProjects {

    val projects: List<OpenSourceProject> = listOf(
        OpenSourceProject(
            name = "Android Jetpack (AndroidX)",
            copyright = "Copyright (C) The Android Open Source Project",
            license = "Apache License 2.0",
            url = "https://developer.android.com/jetpack",
            descriptionRes = dev.woms.mumdroid.R.string.oss_androidx_desc,
        ),
        OpenSourceProject(
            name = "Kotlin",
            copyright = "Copyright (C) JetBrains s.r.o. and Kotlin Programming Language contributors",
            license = "Apache License 2.0",
            url = "https://kotlinlang.org/",
            descriptionRes = dev.woms.mumdroid.R.string.oss_kotlin_desc,
        ),
        OpenSourceProject(
            name = "kotlinx.coroutines",
            copyright = "Copyright 2000-2020 JetBrains s.r.o. and Kotlin Programming Language contributors",
            license = "Apache License 2.0",
            url = "https://github.com/Kotlin/kotlinx.coroutines",
            descriptionRes = dev.woms.mumdroid.R.string.oss_coroutines_desc,
        ),
        OpenSourceProject(
            name = "Concentus (Opus)",
            copyright = "Copyright (c) by various holding parties, including Skype Limited, Xiph.Org Foundation, CSIRO, Microsoft Corporation, Jean-Marc Valin, Gregory Maxwell, Mark Borgerding, Timothy B. Terriberry, Logan Stromberg",
            license = "BSD 3-Clause",
            url = "https://github.com/lostromb/concentus",
            descriptionRes = dev.woms.mumdroid.R.string.oss_opus_desc,
        ),
        OpenSourceProject(
            name = "RNNoise",
            copyright = "Copyright (c) 2007-2017, 2024 Jean-Marc Valin, Amazon, Mozilla, Xiph.Org Foundation and contributors",
            license = "BSD 3-Clause",
            url = "https://github.com/xiph/rnnoise",
            descriptionRes = dev.woms.mumdroid.R.string.oss_rnnoise_desc,
        ),
        OpenSourceProject(
            name = "SpeexDSP",
            copyright = "Copyright 2002-2008 Xiph.org Foundation, Jean-Marc Valin, Analog Devices Inc., CSIRO, David Rowe, EpicGames, Jutta Degener and Carsten Bormann",
            license = "BSD 3-Clause",
            url = "https://github.com/xiph/speexdsp",
            descriptionRes = dev.woms.mumdroid.R.string.oss_speexdsp_desc,
        ),
        OpenSourceProject(
            name = "Mumble",
            copyright = "Copyright (C) The Mumble Developers. All rights reserved.",
            license = "BSD 3-Clause",
            url = "https://www.mumble.info/",
            descriptionRes = dev.woms.mumdroid.R.string.oss_mumble_desc,
        ),
        OpenSourceProject(
            name = "Room",
            copyright = "Copyright (C) The Android Open Source Project",
            license = "Apache License 2.0",
            url = "https://developer.android.com/jetpack/androidx/releases/room",
            descriptionRes = dev.woms.mumdroid.R.string.oss_room_desc,
        ),
        OpenSourceProject(
            name = "DataStore",
            copyright = "Copyright (C) The Android Open Source Project",
            license = "Apache License 2.0",
            url = "https://developer.android.com/topic/libraries/architecture/datastore",
            descriptionRes = dev.woms.mumdroid.R.string.oss_datastore_desc,
        ),
        OpenSourceProject(
            name = "Protocol Buffers (Lite)",
            copyright = "Copyright 2008 Google Inc.",
            license = "BSD 3-Clause",
            url = "https://github.com/protocolbuffers/protobuf",
            descriptionRes = dev.woms.mumdroid.R.string.oss_protobuf_desc,
        ),
        OpenSourceProject(
            name = "Bouncy Castle",
            copyright = "Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc.",
            license = "MIT License",
            url = "https://www.bouncycastle.org/",
            descriptionRes = dev.woms.mumdroid.R.string.oss_bouncycastle_desc,
        ),
        OpenSourceProject(
            name = "Material Design",
            copyright = "Copyright (C) Google LLC",
            license = "Apache License 2.0",
            url = "https://material.io/",
            descriptionRes = dev.woms.mumdroid.R.string.oss_material_desc,
        ),
    )
}
