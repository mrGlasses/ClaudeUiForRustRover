# Disclaimer and terms of use

**Claude UI for RustRover** ("the plugin") is an unofficial, community-made plugin. It is **not affiliated with, endorsed by or
supported by Anthropic or JetBrains**. "Claude" and "Claude Code" are trademarks of Anthropic; "RustRover" is a trademark of JetBrains.

## What the plugin does

The plugin connects RustRover to **your own installation of Claude Code** (the `claude` command-line tool). Claude Code is an AI
agent that can **read, create, modify and delete files and run commands on your computer**, within the permissions you grant it.
The plugin shows Claude's proposals and permission requests in the IDE; **you decide** what to accept.

AI output can be wrong, incomplete or harmful. Review every change and command before approving it, keep your work under version
control (for example Git) and keep backups.

## No warranty

The plugin is provided **"AS IS", without warranty of any kind**, express or implied, including but not limited to the warranties of
merchantability, fitness for a particular purpose and non-infringement.

## Limitation of liability

To the maximum extent permitted by applicable law, the authors and contributors **are not liable for any loss or damage of any kind**
arising from or related to the use of, or inability to use, the plugin, including but not limited to:

- loss, corruption or deletion of data, files or source code;
- damage to computers, systems, repositories or projects;
- commands executed or changes made by Claude Code, including ones you approved;
- costs or charges from your use of Claude, Claude Code or any other service (subscriptions, API usage, rate limits);
- security incidents, data exposure or prompt-injection attacks originating from content Claude processes;
- lost profits, business interruption or any direct, indirect, incidental, special or consequential damages.

You use the plugin **at your own risk** and are solely responsible for the actions you approve and for your data.

## Third-party services

Your use of Claude and Claude Code is governed by Anthropic's own terms and policies, and your use of RustRover by JetBrains' terms.
The plugin does not change those terms and does not provide any Anthropic service itself.

## Privacy

The plugin itself sends no data to any server. It talks only to the Claude Code process on your own computer, over local
connections (loopback, `127.0.0.1`). What Claude Code sends to Anthropic is governed by Claude Code and Anthropic's policies.
The plugin's debug log (`~/.claude-meta/bridge.log`) stays on your computer and never contains message contents or credentials.

## Acceptance

By installing or using the plugin you accept these terms and the terms of the license in `LICENSE`. If you do not accept them,
do not install or use the plugin.

*This text is not legal advice. Where local law (for example consumer protection law) does not allow some of these exclusions,
they apply only to the extent permitted.*
