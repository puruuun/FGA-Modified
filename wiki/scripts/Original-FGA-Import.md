# Import original FGA data

[한국어](Original-FGA-Import-KR.md) | [English](Original-FGA-Import.md)

Open **More options → Additional features → Import original FGA data**. Choose **Scripts only**, **Support images only**, or **Scripts and support images**.

1. For scripts, first use **Export all** in the original FGA battle configuration list. Select the folder containing the exported `.fga` files in the import dialog. Subfolders are included.
2. For images, select the original FGA storage folder or its `support` subfolder. Images under `servant`, `ce`, and `friend`, including servant variant subfolders, are copied into the current FGA(mod) storage folder.
3. Select both folders if importing both categories, then press **Import**. The result shows imported, skipped and failed counts for each category, with filenames and reasons for errors. A bad file does not prevent other files from importing.

Set the destination folder in **More options → Storage** before importing images. Script-only import does not require an image or destination folder.

Existing scripts are retained. Identical stored configurations, including names, are skipped. Different configurations with the same name are added as separate scripts. Existing images are skipped unless **Overwrite existing images with the same name** is selected. Identical images and source/destination files are not rewritten even with overwriting enabled. A destination nested inside the source images is rejected.

Image names and script references are preserved. Review support selections if any referenced images are missing, skipped or failed.

There is no build-number restriction. Legacy flat JSON exports and the current `.fga` layout are supported. Scripts using commands this build cannot parse are reported as failures. Future upstream file format or command syntax changes may require compatibility updates.

The importer reads exported files and folders granted by the user. It does not read another application's private data or transfer global settings, AP refill settings or Android permissions.

Automated tests cover all three modes, legacy JSON, duplicates, naming, partial failures and image conflicts. The Android document picker and device-specific storage providers still need device validation.
