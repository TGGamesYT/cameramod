
<h1 align="center">
  <sub>
    <img src="src/main/resources/assets/cameramod/textures/item/camera_item.png" width="150">
  </sub>
  <br>
  Minecraft Virtualcam
</h1>

This is a minecraft mod for *1.21.8 Fabric*, which registers a *virtual camera* into the Windows system using [softcam](https://github.com/tshino/softcam) and streams a camera entity's rendered POV into it.

<p align="center">
  <a href="https://github.com/tggamesyt/cameramod/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/tggamesyt/cameramod?style=for-the-badge" alt="License">
  </a>
  <a href="https://github.com/tggamesyt/cameramod/releases">
    <img src="https://img.shields.io/github/v/release/tggamesyt/cameramod?style=for-the-badge" alt="Release">
  </a>
  <a href="https://github.com/tggamesyt/cameramod/releases">
    <img src="https://img.shields.io/github/downloads/tggamesyt/cameramod/total?style=for-the-badge" alt="Downloads">
  </a>
</p>

<sub>Inspired by [Flashz's omegle mod](https://youtube.com/@flashzyt)</sub>

## Setup
- Download the latest version of the mod from [here](https://github.com/tggamesyt/cameramod/releases/latest)
- Open the game with the mod, 1.21.8 fabric.
- When prompted, allow ```Softcam_installer.exe``` to run, this registers the virtual webcam.
- Restart your computer

to unregister the webcam, run ```%appdata%/.minecraft_cameramod/uninstall_camera.bat```

## Usage
Once you have the *virtual camera* registered, you can just go into a world, place a camera down, and activate it using the camera activator.
I also added some utility items for rotating and moving the camera around.

## Development notice
when cloning the repo, if you wish to rebuild the natives (dll-s and exe-s) from softcam, use ```--recurse-submodules```.
to rebuild the ```src/main/resources/natives```, run ```.\build_softcam.bat``` with **VS 2022** and **Windows SDK** installed.
