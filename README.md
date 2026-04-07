# Minecraft Virtualcam
This is a minecraft mod for 1.21.8 fabric, which registers a virtual camera into the windows system using [softcam](https://github.com/tshino/softcam) and streams a camera entity's rendered POV into it.

## Setup
on first game launch, the mod auto registers the softcam.dll using softcam_installer.exe
all files are located in ```%appdata%/.minecraft_cameramod``` including an uninstall_camera.bat for uninstalling the dll.
after registering/unregistering the virtual camera, a system restart is required to apply the changes and for windows to realise what happened.

## Usage
Once you have the virtual camera registered, you can just go into a world, place a camera down, and activate it using the camera activator.
I also added some utility items for rotating and moving the camera around.

## Development notice
to rebuild the src/main/resources/natives run .\build_softcam.bat with vscode 2022 and windows 10 sdk installed.
