#include "game_runner.h"

static std::vector<std::unique_ptr<Strategy>> make_strategies_from_names(const std::vector<std::string> &names)
{
	std::vector<std::unique_ptr<Strategy>> strategies(names.size());
	for (auto &name : names)
	{
		strategies.push_back(Factory<Strategy, std::string, std::function<std::unique_ptr<Strategy>()>>::get_instance()->create_product_by_id(name));
	}
	return strategies;
}

static void add_to_global_scores(std::map<std::string, int> &total_scores, const std::vector<std::string> &names, const Result &result)
{
	for (std::size_t i = 0; i < names.size(); ++i)
	{
		auto it = total_scores.find(names[i]);
		if (it == total_scores.end())
		{
			throw std::invalid_argument("invalid"); //impossible???
		}
		it->second += result.scores_[i];
	}
}

static void print_after_game(const std::vector<std::string> &names, const Result &result) noexcept
{
	std::cout << std::string("FINAL SCORES") << std::endl;
	for (std::size_t i = 0; i < names.size(); ++i)
	{
		std::cout << names[i] + "has " << result.scores_[i] << std::endl;
	}
}

static void print_final(const std::map<std::string, int> &map) noexcept
{
	std::cout << "FINAL RESULTS FOR ALL STRATEGIES" << std::endl;
	for (auto &strategy : map)
	{
		std::cout << strategy.first + " has " << strategy.second << " points" <<std::endl;
	}
}

Fast_runner::Fast_runner(const Matrix &matrix, std::vector<std::string> names, std::size_t steps) : game(matrix, make_strategies_from_names(names)), names_(std::move(names)), steps_(steps)
{
	assert(names.size() == 3);
}

Tournament_runner::Tournament_runner(const Matrix &matrix, std::vector<std::string> names, std::size_t steps) :names_(std::move(names)), steps_(steps), matrix_(matrix)
{
	assert(names.size() >= 3);
}

Detailed_runner::Detailed_runner(const Matrix &matrix, std::vector<std::string> names) :game(matrix, make_strategies_from_names(names)), names_(std::move(names))
{
	assert(names.size() == 3);
}

void Fast_runner::run(CLI &ui)
{
	for (std::size_t i = 0; i < steps_; ++i)
	{
		game.step();
	}
	print_after_game(names_, game.get_result());
}

void Detailed_runner::run(CLI &ui)
{
	while (ui.read_msg())
	{
		game.step();
		print_intermediate(game.get_result());
	}
	print_after_game(names_, game.get_result());
}

void Detailed_runner::print_intermediate(const Result &result) const noexcept
{
	std::cout << "--------------" << std::endl;
	for (std::size_t i = 0; i < names_.size(); ++i)
	{
		std::string choice = "cooperate";
		if (Choice::DEFECT == result.choices_[i])
		{
			choice = "defect";
		}
		std::cout << names_[i] + " chose to" + choice + ", got" <<
			result.payoffs_[i] << "points in this round and has " << result.scores_[i] << " in total" << std::endl;
	}
	std::cout << "--------------" << std::endl;
}

void Tournament_runner::run(CLI &ui)
{
	std::map<std::string, int> total_scores;
	for (auto &name : names_)
	{
		total_scores.insert({name, 0});
	}

	std::vector<bool> bool_vec(names_.size());
	std::fill(bool_vec.end() - COLS, bool_vec.end(), true);
	while (std::next_permutation(bool_vec.begin(), bool_vec.end()))
	{
		std::vector<std::string> names(COLS);
		for(std::size_t i = 0; i < bool_vec.size(); ++i)
		{
			if (bool_vec[i])
			{
				names.push_back(names_[i]);
			}
		}
		Game game(matrix_, make_strategies_from_names(names));
		for (std::size_t i = 0; i < steps_; ++i)
		{
			game.step();
		}
		add_to_global_scores(total_scores, names, game.get_result());
		print_after_game(names, game.get_result());
	}
	print_final(total_scores);
}
